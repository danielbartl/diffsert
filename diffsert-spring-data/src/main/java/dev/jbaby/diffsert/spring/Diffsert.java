package dev.jbaby.diffsert.spring;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.data.mongodb.core.convert.MongoTypeMapper;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.WriteModel;

import dev.jbaby.diffsert.BatchWriteResult;
import dev.jbaby.diffsert.DiffsertListener;
import dev.jbaby.diffsert.DiffsertOptions;
import dev.jbaby.diffsert.DiffsertWriter;
import dev.jbaby.diffsert.WriteOutcome;

/**
 * Change-aware upserts for Spring Data MongoDB entities: only fields that really changed reach the oplog, unchanged
 * entities cause no write and no change event. See {@link DiffsertWriter} for the mechanism.
 * <p>
 * Entities are converted with Spring Data's {@link MongoConverter}, so {@code @Id}, {@code @Field} and custom
 * converters apply as with {@code save()}. Every entity must have an id. {@link Document}s are written as they are.
 * Spring Data's type key (usually {@code _class}) is removed by default, see {@link #withTypeKeyRemoved(boolean)}.
 * <p>
 * Driver exceptions such as {@link com.mongodb.MongoBulkWriteException} are thrown as-is (not translated to Spring's
 * {@code DataAccessException}), so failed operations can be inspected via {@code getWriteErrors()}.
 * <p>
 * Instances are immutable and thread-safe.
 */
public final class Diffsert {

    private final MongoOperations mongo;
    private final DiffsertWriter writer;
    private final boolean removeTypeKey;

    public Diffsert(MongoOperations mongo) {
        this(mongo, DiffsertOptions.defaults());
    }

    public Diffsert(MongoOperations mongo, DiffsertOptions options) {
        this(mongo, new DiffsertWriter(options), true);
    }

    private Diffsert(MongoOperations mongo, DiffsertWriter writer, boolean removeTypeKey) {
        if (mongo == null) {
            throw new IllegalArgumentException("mongo must not be null");
        }
        this.mongo = mongo;
        this.writer = writer;
        this.removeTypeKey = removeTypeKey;
    }

    public DiffsertOptions options() {
        return writer.options();
    }

    /** The plain-driver writer this instance delegates to, e.g. to write {@link Document}s to any collection. */
    public DiffsertWriter writer() {
        return writer;
    }

    public boolean removesTypeKey() {
        return removeTypeKey;
    }

    /** Returns an instance for the same database with different options. */
    public Diffsert withOptions(DiffsertOptions newOptions) {
        return new Diffsert(mongo, writer.withOptions(newOptions), removeTypeKey);
    }

    /** Returns an instance that reports every write to {@code listener} (replacing the current one). */
    public Diffsert withListener(DiffsertListener listener) {
        return new Diffsert(mongo, writer.withListener(listener), removeTypeKey);
    }

    /** Returns an instance that removes (default) or keeps Spring Data's type key in converted entities. */
    public Diffsert withTypeKeyRemoved(boolean remove) {
        return new Diffsert(mongo, writer, remove);
    }

    // ---------------------------------------------------------------- single entity

    /** Inserts or updates one entity in the collection mapped to its class. */
    public WriteOutcome upsert(Object entity) {
        return upsert(entity, collectionNameOf(entity.getClass()));
    }

    public WriteOutcome upsert(Object entity, String collectionName) {
        return writer.upsert(collection(collectionName), toDocument(entity));
    }

    /** Updates one entity if it exists; never inserts. */
    public WriteOutcome update(Object entity) {
        return update(entity, collectionNameOf(entity.getClass()));
    }

    public WriteOutcome update(Object entity, String collectionName) {
        return writer.update(collection(collectionName), toDocument(entity));
    }

    // ---------------------------------------------------------------- batch

    /** Inserts or updates all entities with one bulk write. Collection is the one mapped to {@code entityType}. */
    public <T> BatchWriteResult upsertAll(Collection<? extends T> entities, Class<T> entityType) {
        return upsertAll(entities, collectionNameOf(entityType));
    }

    public BatchWriteResult upsertAll(Collection<?> entities, String collectionName) {
        return writer.upsertAll(collection(collectionName), toDocuments(entities));
    }

    /** Updates all entities that exist with one bulk write; never inserts. */
    public <T> BatchWriteResult updateAll(Collection<? extends T> entities, Class<T> entityType) {
        return updateAll(entities, collectionNameOf(entityType));
    }

    public BatchWriteResult updateAll(Collection<?> entities, String collectionName) {
        return writer.updateAll(collection(collectionName), toDocuments(entities));
    }

    // ---------------------------------------------------------------- building blocks

    /**
     * Builds the write models without executing them, e.g. to run them in a session or together with other
     * operations in your own {@code bulkWrite}.
     */
    public List<WriteModel<Document>> toWriteModels(Collection<?> entities, boolean upsert) {
        return writer.toWriteModels(toDocuments(entities), upsert);
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
            if (removeTypeKey) {
                MongoTypeMapper typeMapper = converter.getTypeMapper();
                doc.keySet().removeIf(typeMapper::isTypeKey);
            }
        }
        if (doc.get("_id") == null) {
            throw new IllegalArgumentException("entity has no id: " + entity);
        }
        return doc;
    }

    // ---------------------------------------------------------------- internals

    private List<Document> toDocuments(Collection<?> entities) {
        if (entities == null) {
            return List.of();
        }
        List<Document> docs = new ArrayList<>(entities.size());
        for (Object entity : entities) {
            docs.add(toDocument(entity));
        }
        return docs;
    }

    private MongoCollection<Document> collection(String collectionName) {
        return mongo.getCollection(collectionName);
    }

    private String collectionNameOf(Class<?> type) {
        return mongo.getCollectionName(type);
    }
}
