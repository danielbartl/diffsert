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
import org.springframework.data.mongodb.core.mapping.MongoPersistentEntity;
import org.springframework.data.mongodb.core.mapping.MongoPersistentProperty;

import com.mongodb.WriteConcern;
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
 * The {@code @Version} property of an entity becomes the {@link DiffsertOptions#versionField() version field}: it is
 * incremented with every real change (starting at 0, or 1 for primitive types, as with {@code save()}), so optimistic
 * locking elsewhere in the application notices changes made by Diffsert. Diffsert's own writes don't check it.
 * <p>
 * Writes go through {@link MongoOperations#getCollection(String)}, so they join a Spring-managed transaction
 * ({@code MongoTransactionManager}) like any template operation. Listeners are called when a write runs, not when the
 * transaction commits. The collection's write concern is the one of the client or database: a write concern set on
 * the template ({@code setWriteConcern}, {@code WriteConcernResolver}) doesn't apply, see
 * {@link #withWriteConcern(WriteConcern)}.
 * <p>
 * Only the conversion works like {@code save()}: entity callbacks ({@code BeforeConvertCallback},
 * {@code BeforeSaveCallback}, {@code AfterSaveCallback}), validation and mapping events ({@code BeforeSaveEvent},
 * {@code AfterSaveEvent} etc.) don't run.
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
    private final WriteConcern writeConcern;
    private final Map<Class<?>, EntityAuditing> auditingByType;
    private final Map<Class<?>, DiffsertWriter> writerByType = new ConcurrentHashMap<>();

    public Diffsert(MongoOperations mongo) {
        this(mongo, DiffsertOptions.defaults());
    }

    public Diffsert(MongoOperations mongo, DiffsertOptions options) {
        this(mongo, new DiffsertWriter(options), true, null, null, new ConcurrentHashMap<>());
    }

    private Diffsert(MongoOperations mongo, DiffsertWriter writer, boolean removeTypeKey,
            AuditingHandler auditingHandler, WriteConcern writeConcern,
            Map<Class<?>, EntityAuditing> auditingByType) {
        if (mongo == null) {
            throw new IllegalArgumentException("mongo must not be null");
        }
        this.mongo = mongo;
        this.writer = writer;
        this.removeTypeKey = removeTypeKey;
        this.auditingHandler = auditingHandler;
        this.writeConcern = writeConcern;
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
     * annotations if auditing is enabled, and the field of its {@code @Version} property as version field. For
     * {@link Document}s, always the configured ones.
     *
     * @throws IllegalArgumentException if auditing is enabled and {@code type} has auditing annotations on nested
     *                                  properties, or if {@code type} has a {@code @Version} property and a different
     *                                  version field is configured
     */
    public DiffsertOptions optionsFor(Class<?> type) {
        return writerFor(type).options();
    }

    /** Returns an instance for the same database with different options. */
    public Diffsert withOptions(DiffsertOptions newOptions) {
        return new Diffsert(mongo, writer.withOptions(newOptions), removeTypeKey, auditingHandler, writeConcern,
                auditingByType);
    }

    /** Returns an instance that reports every write to {@code listener} (replacing the current one). */
    public Diffsert withListener(DiffsertListener listener) {
        return new Diffsert(mongo, writer.withListener(listener), removeTypeKey, auditingHandler, writeConcern,
                auditingByType);
    }

    /** Returns an instance that removes (default) or keeps Spring Data's type key in converted entities. */
    public Diffsert withTypeKeyRemoved(boolean remove) {
        return new Diffsert(mongo, writer, remove, auditingHandler, writeConcern, auditingByType);
    }

    /**
     * Returns an instance that writes with {@code writeConcern}, e.g. because the template has its own
     * ({@code setWriteConcern}, {@code WriteConcernResolver}), which doesn't apply to Diffsert's writes. It must be
     * acknowledged. Without it, the write concern of the client or database applies.
     */
    public Diffsert withWriteConcern(WriteConcern writeConcern) {
        if (writeConcern == null) {
            throw new IllegalArgumentException("writeConcern must not be null");
        }
        return new Diffsert(mongo, writer, removeTypeKey, auditingHandler, writeConcern, auditingByType);
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
        return new Diffsert(mongo, writer, removeTypeKey, auditingHandler, writeConcern, auditingByType);
    }

    // ---------------------------------------------------------------- single entity

    /** Inserts or updates one entity in the collection mapped to its class. */
    public WriteOutcome upsert(Object entity) {
        return upsert(entity, collectionNameOf(typeOf(entity)));
    }

    public WriteOutcome upsert(Object entity, String collectionName) {
        Document doc = toDocument(entity);
        return writerFor(entity.getClass()).upsert(collection(collectionName), doc);
    }

    /** Updates one entity if it exists; never inserts. */
    public WriteOutcome update(Object entity) {
        return update(entity, collectionNameOf(typeOf(entity)));
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
        DiffsertWriter batchWriter = null;
        for (Object entity : entities) {
            docs.add(toDocument(entity));
            DiffsertWriter entityWriter = writerFor(entity.getClass());
            if (batchWriter == null) {
                batchWriter = entityWriter;
            } else if (entityWriter != batchWriter && !entityWriter.options().equals(batchWriter.options())) {
                throw new IllegalArgumentException("entities in one batch must have the same auditing and version "
                        + "fields; write " + entity.getClass().getName() + " in a separate batch");
            }
        }
        return new Batch(batchWriter, docs);
    }

    /** The writer with the options for entities of {@code type}, derived once per type. */
    private DiffsertWriter writerFor(Class<?> type) {
        return writerByType.computeIfAbsent(type,
                t -> writerWith(withVersionFieldOf(t, auditingOf(t).applyTo(writer.options()))));
    }

    private DiffsertWriter writerWith(DiffsertOptions options) {
        return options.equals(writer.options()) ? writer : writer.withOptions(options);
    }

    /** {@code options} with the field of {@code type}'s {@code @Version} property as version field, if it has one. */
    private DiffsertOptions withVersionFieldOf(Class<?> type, DiffsertOptions options) {
        if (Map.class.isAssignableFrom(type)) {
            return options;
        }
        MongoPersistentEntity<?> entity = mongo.getConverter().getMappingContext().getPersistentEntity(type);
        if (entity == null || !entity.hasVersionProperty()) {
            return options;
        }
        MongoPersistentProperty version = entity.getRequiredVersionProperty();
        String field = version.getFieldName();
        if (options.versionField() != null && !options.versionField().equals(field)) {
            throw new IllegalArgumentException("version field '" + options.versionField() + "' is configured, but "
                    + type.getName() + " has its @Version property in '" + field + "'");
        }
        return options.toBuilder().versionField(field, initialVersionOf(version)).build();
    }

    /** As {@code save()} initializes it: 1 for primitive types, otherwise 0; an int for int types, else a long. */
    private static Number initialVersionOf(MongoPersistentProperty version) {
        Class<?> type = version.getType();
        int initial = type.isPrimitive() ? 1 : 0;
        if (type == int.class || type == Integer.class) {
            return Integer.valueOf(initial);
        }
        return Long.valueOf(initial);
    }

    private EntityAuditing auditingOf(Class<?> type) {
        if (auditingHandler == null) {
            return EntityAuditing.NONE;
        }
        return auditingByType.computeIfAbsent(type,
                t -> EntityAuditing.of(mongo.getConverter().getMappingContext(), t));
    }

    private static Class<?> typeOf(Object entity) {
        if (entity == null) {
            throw new IllegalArgumentException("entity must not be null");
        }
        return entity.getClass();
    }

    private MongoCollection<Document> collection(String collectionName) {
        MongoCollection<Document> collection = mongo.getCollection(collectionName);
        return writeConcern == null ? collection : collection.withWriteConcern(writeConcern);
    }

    private String collectionNameOf(Class<?> type) {
        return mongo.getCollectionName(type);
    }
}
