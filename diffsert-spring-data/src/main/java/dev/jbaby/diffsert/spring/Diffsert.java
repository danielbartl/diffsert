package dev.jbaby.diffsert.spring;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bson.Document;
import org.springframework.data.auditing.AuditingHandler;
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
 * With {@link #withAuditing(AuditingHandler)} (automatic in the Spring Boot starter with {@code @EnableMongoAuditing}),
 * Spring Data's auditing annotations on top-level properties are handled: {@code @CreatedDate} and {@code @CreatedBy}
 * keep their stored value once set, {@code @LastModifiedDate} and {@code @LastModifiedBy} don't count as a change, so
 * they only move when something else changed.
 * <p>
 * Writes go through {@link MongoOperations#getCollection(String)}, so they join a Spring-managed transaction
 * ({@code MongoTransactionManager}) like any template operation. Listeners are called when a write runs, not when the
 * transaction commits.
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
    private final AuditingHandler auditingHandler;
    private final Map<Class<?>, EntityAuditing> auditingByType;

    public Diffsert(MongoOperations mongo) {
        this(mongo, DiffsertOptions.defaults());
    }

    public Diffsert(MongoOperations mongo, DiffsertOptions options) {
        this(mongo, new DiffsertWriter(options), true, null, new ConcurrentHashMap<>());
    }

    private Diffsert(MongoOperations mongo, DiffsertWriter writer, boolean removeTypeKey,
            AuditingHandler auditingHandler, Map<Class<?>, EntityAuditing> auditingByType) {
        if (mongo == null) {
            throw new IllegalArgumentException("mongo must not be null");
        }
        this.mongo = mongo;
        this.writer = writer;
        this.removeTypeKey = removeTypeKey;
        this.auditingHandler = auditingHandler;
        this.auditingByType = auditingByType;
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

    /**
     * The options used for entities of {@code type}: the configured ones, plus the fields derived from auditing
     * annotations if auditing is enabled. For {@link Document}s, always the configured ones.
     *
     * @throws IllegalArgumentException if auditing is enabled and {@code type} has auditing annotations on nested
     *                                  properties
     */
    public DiffsertOptions optionsFor(Class<?> type) {
        return auditingOf(type).applyTo(writer.options());
    }

    /** Returns an instance for the same database with different options. */
    public Diffsert withOptions(DiffsertOptions newOptions) {
        return new Diffsert(mongo, writer.withOptions(newOptions), removeTypeKey, auditingHandler, auditingByType);
    }

    /** Returns an instance that reports every write to {@code listener} (replacing the current one). */
    public Diffsert withListener(DiffsertListener listener) {
        return new Diffsert(mongo, writer.withListener(listener), removeTypeKey, auditingHandler, auditingByType);
    }

    /** Returns an instance that removes (default) or keeps Spring Data's type key in converted entities. */
    public Diffsert withTypeKeyRemoved(boolean remove) {
        return new Diffsert(mongo, writer, remove, auditingHandler, auditingByType);
    }

    /**
     * Returns an instance that handles Spring Data's auditing annotations on entities, using {@code auditingHandler}
     * (with {@code @EnableMongoAuditing}, the bean {@code mongoAuditingHandler}):
     * <ul>
     * <li>every entity is marked as created (and modified) before conversion, like {@code save()} does for a new
     * entity; mutable entities are changed in place,</li>
     * <li>{@code @CreatedDate} and {@code @CreatedBy} fields are preserved: the stored value is kept once set,</li>
     * <li>{@code @LastModifiedDate} and {@code @LastModifiedBy} fields are ignored for change detection, so they only
     * change when something else changed.</li>
     * </ul>
     * Only top-level properties are supported; entities with nested auditing fields are rejected. {@link Document}s
     * are written as they are.
     */
    public Diffsert withAuditing(AuditingHandler auditingHandler) {
        if (auditingHandler == null) {
            throw new IllegalArgumentException("auditingHandler must not be null");
        }
        return new Diffsert(mongo, writer, removeTypeKey, auditingHandler, auditingByType);
    }

    // ---------------------------------------------------------------- single entity

    /** Inserts or updates one entity in the collection mapped to its class. */
    public WriteOutcome upsert(Object entity) {
        return upsert(entity, collectionNameOf(entity.getClass()));
    }

    public WriteOutcome upsert(Object entity, String collectionName) {
        Document doc = toDocument(entity);
        return writerFor(entity.getClass()).upsert(collection(collectionName), doc);
    }

    /** Updates one entity if it exists; never inserts. */
    public WriteOutcome update(Object entity) {
        return update(entity, collectionNameOf(entity.getClass()));
    }

    public WriteOutcome update(Object entity, String collectionName) {
        Document doc = toDocument(entity);
        return writerFor(entity.getClass()).update(collection(collectionName), doc);
    }

    // ---------------------------------------------------------------- batch

    /** Inserts or updates all entities with one bulk write. Collection is the one mapped to {@code entityType}. */
    public <T> BatchWriteResult upsertAll(Collection<? extends T> entities, Class<T> entityType) {
        return upsertAll(entities, collectionNameOf(entityType));
    }

    public BatchWriteResult upsertAll(Collection<?> entities, String collectionName) {
        Batch batch = prepare(entities);
        return batch.writer().upsertAll(collection(collectionName), batch.docs());
    }

    /** Updates all entities that exist with one bulk write; never inserts. */
    public <T> BatchWriteResult updateAll(Collection<? extends T> entities, Class<T> entityType) {
        return updateAll(entities, collectionNameOf(entityType));
    }

    public BatchWriteResult updateAll(Collection<?> entities, String collectionName) {
        Batch batch = prepare(entities);
        return batch.writer().updateAll(collection(collectionName), batch.docs());
    }

    // ---------------------------------------------------------------- building blocks

    /**
     * Builds the write models without executing them, e.g. to run them in a session or together with other
     * operations in your own {@code bulkWrite}.
     */
    public List<WriteModel<Document>> toWriteModels(Collection<?> entities, boolean upsert) {
        Batch batch = prepare(entities);
        return batch.writer().toWriteModels(batch.docs(), upsert);
    }

    /** Converts an entity into the BSON document that would be written (marked for auditing if enabled). */
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
            EntityAuditing auditing = auditingOf(entity.getClass());
            converter.write(auditing.isEmpty() ? entity : auditing.mark(auditingHandler, entity), doc);
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

    private record Batch(DiffsertWriter writer, List<Document> docs) {
    }

    /** Converts the entities; they share one writer, so they must agree on the fields derived from auditing. */
    private Batch prepare(Collection<?> entities) {
        if (entities == null || entities.isEmpty()) {
            return new Batch(writer, List.of());
        }
        List<Document> docs = new ArrayList<>(entities.size());
        DiffsertOptions batchOptions = null;
        for (Object entity : entities) {
            docs.add(toDocument(entity));
            DiffsertOptions entityOptions = optionsFor(entity.getClass());
            if (batchOptions == null) {
                batchOptions = entityOptions;
            } else if (!batchOptions.equals(entityOptions)) {
                throw new IllegalArgumentException("entities in one batch must have the same auditing fields; write "
                        + entity.getClass().getName() + " in a separate batch");
            }
        }
        return new Batch(writerWith(batchOptions), docs);
    }

    private DiffsertWriter writerFor(Class<?> type) {
        return writerWith(optionsFor(type));
    }

    private DiffsertWriter writerWith(DiffsertOptions options) {
        return options.equals(writer.options()) ? writer : writer.withOptions(options);
    }

    private EntityAuditing auditingOf(Class<?> type) {
        if (auditingHandler == null) {
            return EntityAuditing.NONE;
        }
        return auditingByType.computeIfAbsent(type,
                t -> EntityAuditing.of(mongo.getConverter().getMappingContext(), t));
    }

    private MongoCollection<Document> collection(String collectionName) {
        return mongo.getCollection(collectionName);
    }

    private String collectionNameOf(Class<?> type) {
        return mongo.getCollectionName(type);
    }
}
