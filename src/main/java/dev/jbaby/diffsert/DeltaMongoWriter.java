package dev.jbaby.diffsert;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.bson.BsonValue;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.data.mongodb.core.convert.MongoTypeMapper;

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
 * Fields configured via {@link DeltaWriteOptions#ignoredFields()} don't count as a change on their own, and fields
 * configured via {@link DeltaWriteOptions#preservedFields()} keep their stored value.
 * <p>
 * Entities are converted with Spring Data's {@link MongoConverter}, so {@code @Id}, {@code @Field} and custom
 * converters apply as with {@code save()}. Every entity must have an id.
 * <p>
 * Driver exceptions such as {@link com.mongodb.MongoBulkWriteException} are thrown as-is (not translated to Spring's
 * {@code DataAccessException}), so failed operations can be inspected via {@code getWriteErrors()}.
 * <p>
 * Instances are immutable and thread-safe.
 */
public class DeltaMongoWriter {

    private final MongoOperations mongo;
    private final DeltaWriteOptions options;

    public DeltaMongoWriter(MongoOperations mongo) {
        this(mongo, DeltaWriteOptions.defaults());
    }

    public DeltaMongoWriter(MongoOperations mongo, DeltaWriteOptions options) {
        if (mongo == null || options == null) {
            throw new IllegalArgumentException("mongo and options must not be null");
        }
        this.mongo = mongo;
        this.options = options;
    }

    public DeltaWriteOptions options() {
        return options;
    }

    /** Returns a writer for the same database with different options. */
    public DeltaMongoWriter withOptions(DeltaWriteOptions newOptions) {
        return new DeltaMongoWriter(mongo, newOptions);
    }

    // ---------------------------------------------------------------- single document

    /** Inserts or updates one entity in the collection mapped to its class. */
    public WriteOutcome upsert(Object entity) {
        return upsert(entity, collectionNameOf(entity.getClass()));
    }

    public WriteOutcome upsert(Object entity, String collectionName) {
        return writeOne(entity, collectionName, true);
    }

    /** Updates one entity if it exists; never inserts. */
    public WriteOutcome update(Object entity) {
        return update(entity, collectionNameOf(entity.getClass()));
    }

    public WriteOutcome update(Object entity, String collectionName) {
        return writeOne(entity, collectionName, false);
    }

    // ---------------------------------------------------------------- batch

    /** Inserts or updates all entities with one bulk write. Collection is the one mapped to {@code entityType}. */
    public <T> BatchWriteResult upsertAll(Collection<? extends T> entities, Class<T> entityType) {
        return upsertAll(entities, collectionNameOf(entityType));
    }

    public BatchWriteResult upsertAll(Collection<?> entities, String collectionName) {
        return writeAll(entities, collectionName, true);
    }

    /** Updates all entities that exist with one bulk write; never inserts. */
    public <T> BatchWriteResult updateAll(Collection<? extends T> entities, Class<T> entityType) {
        return updateAll(entities, collectionNameOf(entityType));
    }

    public BatchWriteResult updateAll(Collection<?> entities, String collectionName) {
        return writeAll(entities, collectionName, false);
    }

    // ---------------------------------------------------------------- building blocks

    /**
     * Builds the write models without executing them, e.g. to run them in a session or together with other
     * operations in your own {@code bulkWrite}.
     */
    public List<WriteModel<Document>> toWriteModels(Collection<?> entities, boolean upsert) {
        UpdateOptions updateOptions = new UpdateOptions().upsert(upsert);
        List<WriteModel<Document>> models = new ArrayList<>(entities.size());
        for (Object entity : entities) {
            Document doc = toDocument(entity);
            models.add(new UpdateOneModel<>(Filters.eq("_id", doc.get("_id")), buildPipeline(doc), updateOptions));
        }
        return models;
    }

    /** Converts an entity into the BSON document that would be written. */
    public Document toDocument(Object entity) {
        if (entity == null) {
            throw new IllegalArgumentException("entity must not be null");
        }
        Document doc;
        if (entity instanceof Document d) {
            doc = new Document(d);
        } else {
            doc = new Document();
            MongoConverter converter = mongo.getConverter();
            converter.write(entity, doc);
            if (options.removeTypeKey()) {
                MongoTypeMapper typeMapper = converter.getTypeMapper();
                doc.keySet().removeIf(typeMapper::isTypeKey);
            }
        }
        if (doc.get("_id") == null) {
            throw new IllegalArgumentException("entity has no id: " + entity);
        }
        return doc;
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
        Object incoming = options.mode() == DeltaWriteOptions.Mode.MERGE
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

    private WriteOutcome writeOne(Object entity, String collectionName, boolean upsert) {
        Document doc = toDocument(entity);
        UpdateResult r = collection(collectionName).updateOne(
                Filters.eq("_id", doc.get("_id")),
                buildPipeline(doc),
                new UpdateOptions().upsert(upsert));

        if (r.getUpsertedId() != null) {
            return WriteOutcome.INSERTED;
        }
        if (r.getModifiedCount() > 0) {
            return WriteOutcome.UPDATED;
        }
        return r.getMatchedCount() > 0 ? WriteOutcome.UNCHANGED : WriteOutcome.NOT_FOUND;
    }

    private BatchWriteResult writeAll(Collection<?> entities, String collectionName, boolean upsert) {
        if (entities == null || entities.isEmpty()) {
            return BatchWriteResult.empty();
        }
        BulkWriteResult r = collection(collectionName).bulkWrite(
                toWriteModels(entities, upsert),
                new BulkWriteOptions().ordered(options.ordered()));

        List<BsonValue> insertedIds = r.getUpserts().stream().map(BulkWriteUpsert::getId).toList();
        return new BatchWriteResult(entities.size(), r.getMatchedCount(), r.getModifiedCount(),
                insertedIds.size(), insertedIds);
    }

    private MongoCollection<Document> collection(String collectionName) {
        return mongo.getCollection(collectionName);
    }

    private String collectionNameOf(Class<?> type) {
        return mongo.getCollectionName(type);
    }
}
