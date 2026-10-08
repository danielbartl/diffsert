package dev.jbaby.diffsert;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.bson.BsonValue;
import org.bson.Document;

import com.mongodb.MongoBulkWriteException;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.bulk.BulkWriteUpsert;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.WriteModel;
import com.mongodb.client.result.UpdateResult;

/**
 * Writes documents so that MongoDB only records what actually changed.
 * <p>
 * Every write is sent as a pipeline update ({@code $replaceWith}) instead of a replace. MongoDB (5.0+) compares the
 * result with the stored document and writes only the difference to the oplog. So:
 * <ul>
 * <li>unchanged documents cause no write and no change event,</li>
 * <li>changed documents produce an {@code update} event whose {@code updatedFields}/{@code removedFields} contain only
 * the fields that really changed,</li>
 * <li>new documents (upsert) produce an {@code insert} event.</li>
 * </ul>
 * Fields configured via {@link DiffsertOptions#ignoredFields()} don't count as a change on their own, and fields
 * configured via {@link DiffsertOptions#preservedFields()} keep their stored value.
 * <p>
 * Works with plain driver {@link Document}s; every document must have an {@code _id}. The collection is passed per
 * call, so its codec registry, write concern etc. apply as configured by the caller.
 * <p>
 * Driver exceptions such as {@link com.mongodb.MongoBulkWriteException} are thrown as-is, so failed operations can be
 * inspected via {@code getWriteErrors()}.
 * <p>
 * Instances are immutable and thread-safe.
 */
public final class DiffsertWriter {

    private final DiffsertOptions options;
    private final DiffsertListener listener;

    public DiffsertWriter() {
        this(DiffsertOptions.defaults());
    }

    public DiffsertWriter(DiffsertOptions options) {
        this(options, DiffsertListener.NONE);
    }

    private DiffsertWriter(DiffsertOptions options, DiffsertListener listener) {
        if (options == null || listener == null) {
            throw new IllegalArgumentException("options and listener must not be null");
        }
        this.options = options;
        this.listener = listener;
    }

    public DiffsertOptions options() {
        return options;
    }

    /** Returns a writer with different options and the same listener. */
    public DiffsertWriter withOptions(DiffsertOptions newOptions) {
        return new DiffsertWriter(newOptions, listener);
    }

    /** Returns a writer that reports every write to {@code newListener} (replacing the current one). */
    public DiffsertWriter withListener(DiffsertListener newListener) {
        return new DiffsertWriter(options, newListener);
    }

    // ---------------------------------------------------------------- single document

    /** Inserts or updates one document. */
    public WriteOutcome upsert(MongoCollection<Document> collection, Document doc) {
        return writeOne(collection, doc, true);
    }

    /** Updates one document if it exists; never inserts. */
    public WriteOutcome update(MongoCollection<Document> collection, Document doc) {
        return writeOne(collection, doc, false);
    }

    // ---------------------------------------------------------------- batch

    /** Inserts or updates all documents with one bulk write. */
    public BatchWriteResult upsertAll(MongoCollection<Document> collection, Collection<? extends Document> docs) {
        return writeAll(collection, docs, true);
    }

    /** Updates all documents that exist with one bulk write; never inserts. */
    public BatchWriteResult updateAll(MongoCollection<Document> collection, Collection<? extends Document> docs) {
        return writeAll(collection, docs, false);
    }

    // ---------------------------------------------------------------- building blocks

    /**
     * Builds the write models without executing them, e.g. to run them in a session or together with other
     * operations in your own {@code bulkWrite}. The listener is not called for these.
     */
    public List<WriteModel<Document>> toWriteModels(Collection<? extends Document> docs, boolean upsert) {
        UpdateOptions updateOptions = new UpdateOptions().upsert(upsert);
        List<WriteModel<Document>> models = new ArrayList<>(docs.size());
        for (Document doc : docs) {
            models.add(new UpdateOneModel<>(Filters.eq("_id", idOf(doc)), buildPipeline(doc), updateOptions));
        }
        return models;
    }

    /**
     * The update pipeline for one document. Without ignored/preserved fields it is simply
     * <pre>{@code [{ $replaceWith: { $literal: <doc> } }]}</pre>
     * With them, it computes the result first and keeps the stored document unless something outside the ignored
     * fields differs:
     * <pre>{@code
     * [{ $replaceWith: { $let: {
     *     vars: { incoming: <doc, or $$ROOT merged with doc> },
     *     in: { $let: {
     *       vars: { result: <incoming with stored values of preserved fields> },
     *       in: { $cond: {
     *         if:   <document existed> and <$$ROOT minus ignored fields == result minus ignored fields>,
     *         then: "$$ROOT",
     *         else: "$$result" } } } } } } }]
     * }</pre>
     * {@code $literal} makes sure values starting with {@code $} are never interpreted as expressions.
     */
    public List<Document> buildPipeline(Document doc) {
        Object literal = new Document("$literal", doc);
        Object incoming = options.mode() == DiffsertOptions.Mode.MERGE
                ? new Document("$mergeObjects", List.of("$$ROOT", literal))
                : literal;

        if (options.ignoredFields().isEmpty() && options.preservedFields().isEmpty()) {
            return List.of(new Document("$replaceWith", incoming));
        }

        Object result = "$$incoming";
        for (String field : options.preservedFields()) {
            // { $mergeObjects: [ result, { $cond: [ <stored field missing>, {}, { field: "$field" } ] } ] }
            Object storedValue = new Document("$cond", List.of(
                    new Document("$eq", List.of(new Document("$type", "$" + field), "missing")),
                    new Document(),
                    new Document(field, "$" + field)));
            result = new Document("$mergeObjects", List.of(result, storedValue));
        }

        // On an upsert that inserts, $$ROOT is just { _id: ... }. Never keep that, even if the new document
        // only differs in ignored fields.
        Object documentExisted = new Document("$ne", List.of("$$ROOT", new Document("_id", "$_id")));
        Object nothingRelevantChanged = new Document("$eq", List.of(
                withoutIgnoredFields("$$ROOT"),
                withoutIgnoredFields("$$result")));

        Object keepOrReplace = new Document("$cond", new Document()
                .append("if", new Document("$and", List.of(documentExisted, nothingRelevantChanged)))
                .append("then", "$$ROOT")
                .append("else", "$$result"));

        Object expression = new Document("$let", new Document()
                .append("vars", new Document("incoming", incoming))
                .append("in", new Document("$let", new Document()
                        .append("vars", new Document("result", result))
                        .append("in", keepOrReplace))));

        return List.of(new Document("$replaceWith", expression));
    }

    // ---------------------------------------------------------------- internals

    private Object withoutIgnoredFields(Object input) {
        Object expr = input;
        for (String field : options.ignoredFields()) {
            expr = new Document("$unsetField", new Document("field", field).append("input", expr));
        }
        return expr;
    }

    private static Object idOf(Document doc) {
        if (doc == null) {
            throw new IllegalArgumentException("document must not be null");
        }
        Object id = doc.get("_id");
        if (id == null) {
            throw new IllegalArgumentException("document has no _id: " + doc.toJson());
        }
        return id;
    }

    private WriteOutcome writeOne(MongoCollection<Document> collection, Document doc, boolean upsert) {
        UpdateResult r = collection.updateOne(
                Filters.eq("_id", idOf(doc)),
                buildPipeline(doc),
                new UpdateOptions().upsert(upsert));

        WriteOutcome outcome;
        if (r.getUpsertedId() != null) {
            outcome = WriteOutcome.INSERTED;
        } else if (r.getModifiedCount() > 0) {
            outcome = WriteOutcome.UPDATED;
        } else {
            outcome = r.getMatchedCount() > 0 ? WriteOutcome.UNCHANGED : WriteOutcome.NOT_FOUND;
        }
        listener.onWrite(collectionNameOf(collection), BatchWriteResult.of(outcome, r.getUpsertedId()));
        return outcome;
    }

    private BatchWriteResult writeAll(MongoCollection<Document> collection, Collection<? extends Document> docs,
            boolean upsert) {
        if (docs == null || docs.isEmpty()) {
            return BatchWriteResult.empty();
        }
        List<WriteModel<Document>> models = toWriteModels(docs, upsert);
        BulkWriteResult r;
        try {
            r = collection.bulkWrite(models, new BulkWriteOptions().ordered(options.ordered()));
        } catch (MongoBulkWriteException e) {
            // Some documents may have been written (and emitted change events) before or besides the failed ones.
            // Report those, then rethrow.
            try {
                listener.onWrite(collectionNameOf(collection), toResult(e.getWriteResult(), succeeded(e, docs.size())));
            } catch (RuntimeException listenerFailure) {
                e.addSuppressed(listenerFailure);
            }
            throw e;
        }

        BatchWriteResult result = toResult(r, docs.size());
        listener.onWrite(collectionNameOf(collection), result);
        return result;
    }

    private static BatchWriteResult toResult(BulkWriteResult r, int requested) {
        List<BsonValue> insertedIds = r.getUpserts().stream().map(BulkWriteUpsert::getId).toList();
        return new BatchWriteResult(requested, r.getMatchedCount(), r.getModifiedCount(), insertedIds.size(),
                insertedIds);
    }

    /**
     * Number of operations that were executed without error: an ordered bulk write stops at the first error,
     * an unordered one executes all others. A write concern error alone means every operation was applied.
     */
    private int succeeded(MongoBulkWriteException e, int total) {
        if (e.getWriteErrors().isEmpty()) {
            return total;
        }
        if (options.ordered()) {
            return e.getWriteErrors().get(0).getIndex();
        }
        return total - e.getWriteErrors().size();
    }

    private static String collectionNameOf(MongoCollection<Document> collection) {
        return collection.getNamespace().getCollectionName();
    }
}
