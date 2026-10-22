package dev.jbaby.diffsert;

import java.util.ArrayList;
import java.util.Arrays;
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
 * configured via {@link DiffsertOptions#preservedFields()} keep their stored value. A
 * {@link DiffsertOptions#versionField()} is incremented with every real change.
 * <p>
 * Works with plain driver {@link Document}s; every document must have an {@code _id}, which is written as the first
 * field wherever the document has it. The collection is passed per call, so its codec registry, write concern etc.
 * apply as configured by the caller. The write concern must be acknowledged: an unacknowledged write doesn't tell what
 * it did, so it is rejected before anything is sent.
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
     * The update pipeline for one document. Without ignored, preserved or version fields it is simply
     * <pre>{@code [{ $replaceWith: { $literal: <doc> } }]}</pre>
     * With them, it computes the result first and keeps the stored document unless something outside the ignored
     * fields and the version field differs:
     * <pre>{@code
     * [{ $replaceWith: { $let: {
     *     vars: { incoming: <doc, or $$ROOT merged with doc> },
     *     in: { $let: {
     *       vars: { result: <incoming with stored values of preserved fields> },
     *       in: { $cond: {
     *         if:   <document existed> and <$$ROOT == result, both without ignored fields and the version field>,
     *         then: "$$ROOT",
     *         else: <result, with the version field set to the stored value + 1 or the initial version> } } } } } } }]
     * }</pre>
     * The version field is set with
     * <pre>{@code
     * { $cond: [ { $eq: [ { $ifNull: [ "$version", null ] }, null ] }, <initial>,
     *     { $add: [ { $multiply: [ "$version", 1 ] }, 1 ] } ] }
     * }</pre>
     * so it keeps its position in the incoming document (or is appended), and a stored non-numeric value fails the
     * write ({@code $multiply} rejects dates, which {@code $add} alone would accept).
     * {@code $literal} makes sure values starting with {@code $} are never interpreted as expressions, and {@code _id}
     * is moved to the front of the document: MongoDB always stores it first, so anywhere else, the field order would
     * differ and every write would count as a change.
     */
    public List<Document> buildPipeline(Document doc) {
        Object literal = new Document("$literal", withIdFirst(doc));
        Object incoming = options.mode() == DiffsertOptions.Mode.MERGE
                ? new Document("$mergeObjects", List.of("$$ROOT", literal))
                : literal;

        if (options.ignoredFields().isEmpty() && options.preservedFields().isEmpty()
                && options.versionField() == null) {
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
                withoutUncomparedFields("$$ROOT"),
                withoutUncomparedFields("$$result")));

        Object keepOrReplace = new Document("$cond", new Document()
                .append("if", new Document("$and", List.of(documentExisted, nothingRelevantChanged)))
                .append("then", "$$ROOT")
                .append("else", withNextVersion("$$result")));

        Object expression = new Document("$let", new Document()
                .append("vars", new Document("incoming", incoming))
                .append("in", new Document("$let", new Document()
                        .append("vars", new Document("result", result))
                        .append("in", keepOrReplace))));

        return List.of(new Document("$replaceWith", expression));
    }

    // ---------------------------------------------------------------- internals

    /** {@code input} without the ignored fields and the version field. */
    private Object withoutUncomparedFields(Object input) {
        Object expr = input;
        for (String field : options.ignoredFields()) {
            expr = new Document("$unsetField", new Document("field", field).append("input", expr));
        }
        if (options.versionField() != null) {
            expr = new Document("$unsetField", new Document("field", options.versionField()).append("input", expr));
        }
        return expr;
    }

    /** {@code result} with the version field set to the stored value + 1, or the initial version. */
    private Object withNextVersion(Object result) {
        String field = options.versionField();
        if (field == null) {
            return result;
        }
        Object storedVersion = "$" + field;
        // $multiply fails on anything but a number; $add alone would turn a stored date into date + 1 ms
        Object storedNumber = new Document("$multiply", List.of(storedVersion, 1));
        Object nextVersion = new Document("$cond", List.of(
                new Document("$eq", Arrays.asList(new Document("$ifNull", Arrays.asList(storedVersion, null)), null)),
                options.initialVersion(),
                new Document("$add", List.of(storedNumber, 1))));
        return new Document("$mergeObjects", List.of(result, new Document(field, nextVersion)));
    }

    /** {@code doc}, or a copy of it with {@code _id} moved to the front. */
    private static Document withIdFirst(Document doc) {
        if (!doc.containsKey("_id") || doc.keySet().iterator().next().equals("_id")) {
            return doc;
        }
        Document reordered = new Document("_id", doc.get("_id"));
        reordered.putAll(doc);
        return reordered;
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

    /** Fails before writing if {@code collection} wouldn't tell what a write did (write concern {@code w: 0}). */
    private static void requireAcknowledged(MongoCollection<Document> collection) {
        if (!collection.getWriteConcern().isAcknowledged()) {
            throw new IllegalArgumentException("collection " + collection.getNamespace() + " has an unacknowledged "
                    + "write concern; Diffsert needs to know what each write did");
        }
    }

    private WriteOutcome writeOne(MongoCollection<Document> collection, Document doc, boolean upsert) {
        requireAcknowledged(collection);
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
        requireAcknowledged(collection);
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
