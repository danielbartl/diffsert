package dev.jbaby.diffsert.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.data.auditing.IsNewAwareAuditingHandler;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.OperationType;

import dev.jbaby.diffsert.BatchWriteResult;
import dev.jbaby.diffsert.DiffsertOptions;
import dev.jbaby.diffsert.testsupport.TestMongo;
import dev.jbaby.diffsert.WriteOutcome;

/**
 * Verifies entity writes against a real MongoDB (single-node replica set, so change streams work) by looking at the
 * change events that each write produces.
 */
@Testcontainers
class DiffsertIT {

    @Container
    static final MongoDBContainer MONGO = TestMongo.container();

    static MongoClient client;
    static MongoTemplate template;
    static TransactionTemplate transactions;

    MongoCollection<Document> collection;
    MongoCollection<Document> orders;
    Diffsert writer;

    static final Instant T1 = Instant.parse("2026-10-01T08:00:00Z");
    static final Instant T2 = Instant.parse("2026-10-02T08:00:00Z");

    /** What the auditing handler sees as the current time and user. */
    Instant now = T1;
    String auditor = "alice";

    @org.springframework.data.mongodb.core.mapping.Document("customers")
    record Customer(
            @Id String id,
            String name,
            String city,
            String jobRunId,
            @Field("_createdAt") Instant createdAt,
            @Field("_updatedAt") Instant updatedAt) {

        /** Same business data, metadata of a new batch run. */
        Customer nextRun() {
            Instant now = Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.MILLIS);
            return new Customer(id, name, city, UUID.randomUUID().toString(), now, now);
        }

        Customer withCity(String newCity) {
            return new Customer(id, name, newCity, jobRunId, createdAt, updatedAt);
        }

        Customer withName(String newName) {
            return new Customer(id, newName, city, jobRunId, createdAt, updatedAt);
        }
    }

    /** Audited entity; as mapped from the source system, the auditing fields are left empty. */
    @org.springframework.data.mongodb.core.mapping.Document("orders")
    record Order(
            @Id String id,
            String status,
            @CreatedDate @Field("_createdAt") Instant createdAt,
            @CreatedBy String createdBy,
            @LastModifiedDate @Field("_updatedAt") Instant updatedAt,
            @LastModifiedBy String updatedBy) {

        static Order of(String id, String status) {
            return new Order(id, status, null, null, null, null);
        }
    }

    record Shipping(String carrier, @LastModifiedDate Instant updatedAt) {
    }

    @org.springframework.data.mongodb.core.mapping.Document("orders")
    record ShippedOrder(@Id String id, Shipping shipping) {
    }

    /** Also written by the application with save(), so it has a version for optimistic locking. */
    @org.springframework.data.mongodb.core.mapping.Document("orders")
    record VersionedOrder(@Id String id, String status, @Version Long version) {

        static VersionedOrder of(String id, String status) {
            return new VersionedOrder(id, status, null);
        }

        VersionedOrder withStatus(String newStatus) {
            return new VersionedOrder(id, newStatus, version);
        }
    }

    @org.springframework.data.mongodb.core.mapping.Document("orders")
    record CountedOrder(@Id String id, String status, @Version int version) {
    }

    static Customer customer(String id, String name, String city) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return new Customer(id, name, city, "run-1", now, now);
    }

    @BeforeAll
    static void connect() {
        client = MongoClients.create(MONGO.getReplicaSetUrl("test"));
        // template and transaction manager must share the factory, as in a Spring Boot application
        var factory = new SimpleMongoClientDatabaseFactory(client, "test");
        template = new MongoTemplate(factory);
        transactions = new TransactionTemplate(new MongoTransactionManager(factory));
    }

    @AfterAll
    static void disconnect() {
        client.close();
    }

    @BeforeEach
    void setUp() {
        template.dropCollection(Customer.class);
        template.createCollection(Customer.class);
        collection = template.getCollection("customers");
        template.dropCollection(Order.class);
        template.createCollection(Order.class);
        orders = template.getCollection("orders");
        writer = new Diffsert(template, DiffsertOptions.builder()
                .ignoreForChangeDetection("jobRunId", "_updatedAt")
                .preserveExistingValue("_createdAt")
                .build());
    }

    @Test
    void rejectsNullEntity() {
        assertThrows(IllegalArgumentException.class, () -> writer.upsert(null));
        assertThrows(IllegalArgumentException.class, () -> writer.update(null));
    }

    @Test
    void insertsNewDocument() {
        Customer a = customer("c1", "Acme", "Berlin");

        var events = eventsDuring(() -> assertEquals(WriteOutcome.INSERTED, writer.upsert(a)));

        assertEquals(1, events.size());
        assertEquals(OperationType.INSERT, events.get(0).getOperationType());
        assertEquals("Acme", stored("c1").getString("name"));
        assertTrue(stored("c1").containsKey("_createdAt"));
        assertTrue(!stored("c1").containsKey("_class"), "type key must be removed");
    }

    @Test
    void doesNothingWhenOnlyIgnoredFieldsDiffer() {
        Customer a = customer("c1", "Acme", "Berlin");
        writer.upsert(a);

        var events = eventsDuring(() -> assertEquals(WriteOutcome.UNCHANGED, writer.upsert(a.nextRun())));

        assertEquals(List.of(), events);
        assertEquals("run-1", stored("c1").getString("jobRunId"), "unchanged document keeps old run metadata");
    }

    @Test
    void reportsOnlyChangedFieldsAndKeepsCreatedAt() {
        Customer a = customer("c1", "Acme", "Berlin");
        writer.upsert(a);
        Document before = stored("c1");

        var events = eventsDuring(() ->
                assertEquals(WriteOutcome.UPDATED, writer.upsert(a.nextRun().withCity("Hamburg"))));

        assertEquals(1, events.size());
        ChangeStreamDocument<Document> ev = events.get(0);
        assertEquals(OperationType.UPDATE, ev.getOperationType());
        assertEquals(Set.of("city", "jobRunId", "_updatedAt"), ev.getUpdateDescription().getUpdatedFields().keySet());
        assertEquals(List.of(), ev.getUpdateDescription().getRemovedFields());

        Document after = stored("c1");
        assertEquals("Hamburg", after.getString("city"));
        assertEquals(before.get("_createdAt"), after.get("_createdAt"));
        assertEquals(List.copyOf(before.keySet()), List.copyOf(after.keySet()), "field order must stay stable");
    }

    @Test
    void removesFieldsMissingFromNewDocument() {
        Customer a = customer("c1", "Acme", "Berlin");
        writer.upsert(a);

        var events = eventsDuring(() -> writer.upsert(a.withName(null)));

        assertEquals(1, events.size());
        assertEquals(List.of("name"), events.get(0).getUpdateDescription().getRemovedFields());
    }

    @Test
    void batchWritesOnlyNewAndChangedDocuments() {
        Customer unchanged = customer("c1", "Acme", "Berlin");
        Customer changed = customer("c2", "Globex", "Munich");
        writer.upsertAll(List.of(unchanged, changed), Customer.class);

        List<Customer> batch = List.of(
                unchanged.nextRun(),
                changed.nextRun().withCity("Cologne"),
                customer("c3", "Initech", "Frankfurt"));

        BatchWriteResult[] result = new BatchWriteResult[1];
        var events = eventsDuring(() -> result[0] = writer.upsertAll(batch, Customer.class));

        assertEquals(3, result[0].requested());
        assertEquals(1, result[0].inserted());
        assertEquals(1, result[0].modified());
        assertEquals(1, result[0].unchanged());
        assertEquals(0, result[0].notFound());

        assertEquals(2, events.size());
        assertEquals(Set.of("c2", "c3"), Set.of(
                events.get(0).getDocumentKey().getString("_id").getValue(),
                events.get(1).getDocumentKey().getString("_id").getValue()));
    }

    @Test
    void updateNeverInserts() {
        assertEquals(WriteOutcome.NOT_FOUND, writer.update(customer("missing", "X", "Y")));
        assertEquals(0, collection.countDocuments());

        BatchWriteResult r = writer.updateAll(List.of(customer("m1", "X", "Y")), Customer.class);
        assertEquals(1, r.notFound());
        assertEquals(0, collection.countDocuments());
    }

    @Test
    void mergeModeKeepsFieldsWrittenByOthers() {
        collection.insertOne(new Document("_id", "c1").append("name", "Acme").append("foreign", 42));
        Diffsert merging = writer.withOptions(writer.options().toBuilder().merge().build());

        merging.upsert(customer("c1", "Acme", "Berlin"));

        assertEquals(42, stored("c1").getInteger("foreign").intValue());
        assertEquals("Berlin", stored("c1").getString("city"));
    }

    @Test
    void writerWithoutOptionsBehavesLikePlainDeltaReplace() {
        Diffsert plain = new Diffsert(template);
        Customer a = customer("c1", "Acme", "Berlin");
        plain.upsert(a);

        assertEquals(WriteOutcome.UNCHANGED, plain.upsert(a));
        assertEquals(WriteOutcome.UPDATED, plain.upsert(a.nextRun()));
    }

    @Test
    void keepsTypeKeyWhenAsked() {
        writer.withTypeKeyRemoved(false).upsert(customer("c1", "Acme", "Berlin"));

        assertEquals(Customer.class.getName(), stored("c1").getString("_class"));
    }

    @Test
    void reportsWritesToListener() {
        List<String> reported = new ArrayList<>();
        Diffsert listening = writer.withListener((collectionName, result) -> reported.add(collectionName + ": " + result));
        Customer a = customer("c1", "Acme", "Berlin");

        listening.upsert(a);
        listening.upsertAll(List.of(a.nextRun(), customer("c2", "Globex", "Munich")), Customer.class);

        assertEquals(List.of(
                "customers: requested=1, inserted=1, updated=0, unchanged=0, notFound=0",
                "customers: requested=2, inserted=1, updated=0, unchanged=1, notFound=0"), reported);
    }

    @Test
    void joinsSpringManagedTransaction() {
        Customer unchanged = customer("c1", "Acme", "Berlin");
        Customer changed = customer("c2", "Globex", "Munich");
        writer.upsertAll(List.of(unchanged, changed), Customer.class);

        var events = eventsDuring(() -> transactions.executeWithoutResult(status -> {
            writer.upsertAll(List.of(unchanged.nextRun(), changed.nextRun().withCity("Cologne")), Customer.class);
            writer.upsert(customer("c3", "Initech", "Frankfurt"));
            assertEquals(2, collection.countDocuments(), "writes must not be visible before the commit");
        }));

        assertEquals(2, events.size());
        events.forEach(ev -> assertNotNull(ev.getTxnNumber(), "event must come from the transaction: " + ev));
        assertEquals(OperationType.UPDATE, events.get(0).getOperationType());
        assertEquals(Set.of("city", "jobRunId", "_updatedAt"),
                events.get(0).getUpdateDescription().getUpdatedFields().keySet());
        assertEquals(OperationType.INSERT, events.get(1).getOperationType());
        assertEquals(3, collection.countDocuments());
    }

    @Test
    void rollbackDiscardsWritesButListenersHaveSeenThem() {
        Customer a = customer("c1", "Acme", "Berlin");
        writer.upsert(a);
        List<String> reported = new ArrayList<>();
        Diffsert listening = writer.withListener((collectionName, result) -> reported.add(result.toString()));

        var events = eventsDuring(() -> transactions.executeWithoutResult(status -> {
            listening.upsertAll(List.of(a.withCity("Hamburg"), customer("c2", "Globex", "Munich")), Customer.class);
            status.setRollbackOnly();
        }));

        assertEquals(List.of(), events);
        assertEquals("Berlin", stored("c1").getString("city"));
        assertEquals(1, collection.countDocuments());
        assertEquals(List.of("requested=2, inserted=1, updated=1, unchanged=0, notFound=0"), reported,
                "listeners are called when a write runs, not when the transaction commits");
    }

    @Test
    void auditingSetsCreatedAndModifiedFieldsOnInsert() {
        Diffsert audited = new Diffsert(template).withAuditing(auditingHandler());

        var events = TestMongo.eventsDuring(orders,
                () -> assertEquals(WriteOutcome.INSERTED, audited.upsert(Order.of("o1", "open"))));

        assertEquals(1, events.size());
        assertEquals(OperationType.INSERT, events.get(0).getOperationType());
        Document stored = storedOrder("o1");
        assertEquals(Date.from(T1), stored.get("_createdAt"));
        assertEquals("alice", stored.getString("createdBy"));
        assertEquals(Date.from(T1), stored.get("_updatedAt"));
        assertEquals("alice", stored.getString("updatedBy"));

        DiffsertOptions derived = audited.optionsFor(Order.class);
        assertEquals(Set.of("_createdAt", "createdBy"), derived.preservedFields());
        assertEquals(Set.of("_updatedAt", "updatedBy"), derived.ignoredFields());
    }

    @Test
    void auditingFieldsAloneAreNoChange() {
        Diffsert audited = new Diffsert(template).withAuditing(auditingHandler());
        audited.upsert(Order.of("o1", "open"));
        now = T2;
        auditor = "bob";

        var events = TestMongo.eventsDuring(orders,
                () -> assertEquals(WriteOutcome.UNCHANGED, audited.upsert(Order.of("o1", "open"))));

        assertEquals(List.of(), events);
        assertEquals(Date.from(T1), storedOrder("o1").get("_updatedAt"));
        assertEquals("alice", storedOrder("o1").getString("updatedBy"));
    }

    @Test
    void auditingMovesModifiedFieldsOnlyWithRealChanges() {
        Diffsert audited = new Diffsert(template).withAuditing(auditingHandler());
        audited.upsert(Order.of("o1", "open"));
        now = T2;
        auditor = "bob";

        var events = TestMongo.eventsDuring(orders,
                () -> assertEquals(WriteOutcome.UPDATED, audited.upsert(Order.of("o1", "shipped"))));

        assertEquals(1, events.size());
        assertEquals(Set.of("status", "_updatedAt", "updatedBy"),
                events.get(0).getUpdateDescription().getUpdatedFields().keySet());
        Document stored = storedOrder("o1");
        assertEquals(Date.from(T1), stored.get("_createdAt"));
        assertEquals("alice", stored.getString("createdBy"));
        assertEquals(Date.from(T2), stored.get("_updatedAt"));
        assertEquals("bob", stored.getString("updatedBy"));
    }

    @Test
    void auditingSetsModifiedFieldsEvenWithoutModifyOnCreate() {
        AuditingHandler handler = auditingHandler();
        handler.setModifyOnCreation(false);
        Diffsert audited = new Diffsert(template).withAuditing(handler);
        audited.upsert(Order.of("o1", "open"));
        now = T2;

        audited.upsert(Order.of("o1", "shipped"));

        // whether a document is new is only known on the server, so a real change must not remove these
        assertEquals(Date.from(T2), storedOrder("o1").get("_updatedAt"));
        assertEquals(Date.from(T1), storedOrder("o1").get("_createdAt"));
    }

    @Test
    void auditingReplacesLoadedModifiedFieldsWithoutModifyOnCreate() {
        AuditingHandler handler = auditingHandler();
        handler.setModifyOnCreation(false);
        Diffsert audited = new Diffsert(template).withAuditing(handler);
        audited.upsert(Order.of("o1", "open"));
        Order loaded = template.findById("o1", Order.class);
        now = T2;
        auditor = "bob";

        audited.upsert(new Order(loaded.id(), "shipped", loaded.createdAt(), loaded.createdBy(), loaded.updatedAt(),
                loaded.updatedBy()));

        assertEquals(Date.from(T2), storedOrder("o1").get("_updatedAt"), "not the loaded value");
        assertEquals("bob", storedOrder("o1").getString("updatedBy"));
        assertEquals(Date.from(T1), storedOrder("o1").get("_createdAt"));
    }

    @Test
    void auditingLeavesDocumentsAlone() {
        Diffsert audited = new Diffsert(template).withAuditing(auditingHandler());
        assertEquals(audited.options(), audited.optionsFor(Document.class));

        audited.upsert(new Document("_id", "o1").append("status", "open").append("_updatedAt", Date.from(T1)),
                "orders");
        WriteOutcome second = audited.upsert(
                new Document("_id", "o1").append("status", "open").append("_updatedAt", Date.from(T2)), "orders");

        assertEquals(WriteOutcome.UPDATED, second, "_updatedAt is not ignored for a Document");
        assertTrue(!storedOrder("o1").containsKey("_createdAt"));
    }

    @Test
    void auditingNeedsToBeEnabled() {
        Diffsert plain = new Diffsert(template);
        assertEquals(plain.options(), plain.optionsFor(Order.class));

        plain.upsert(Order.of("o1", "open"));

        assertEquals(Set.of("_id", "status"), storedOrder("o1").keySet());
    }

    @Test
    void auditingRejectsNestedFieldsAndMixedBatches() {
        Diffsert audited = new Diffsert(template).withAuditing(auditingHandler());

        var nested = assertThrows(IllegalArgumentException.class,
                () -> audited.upsert(new ShippedOrder("o1", new Shipping("DHL", null))));
        assertTrue(nested.getMessage().contains("shipping.updatedAt"), nested.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> audited.upsertAll(List.of(Order.of("o1", "open"), customer("c1", "Acme", "Berlin")), "orders"));
        assertEquals(0, orders.countDocuments());
    }

    @Test
    void versionCountsRealChangesSoSaveDetectsThem() {
        // the application writes these documents with save() too, so the type key stays
        Diffsert versioned = new Diffsert(template).withTypeKeyRemoved(false);
        assertEquals("version", versioned.optionsFor(VersionedOrder.class).versionField());
        assertEquals(WriteOutcome.INSERTED, versioned.upsert(VersionedOrder.of("o1", "open")));
        assertEquals(0L, storedOrder("o1").get("version"));

        VersionedOrder saved = template.save(template.findById("o1", VersionedOrder.class).withStatus("paid"));
        assertEquals(1L, saved.version());
        assertEquals(WriteOutcome.UNCHANGED, versioned.upsert(VersionedOrder.of("o1", "paid")));

        var events = TestMongo.eventsDuring(orders,
                () -> assertEquals(WriteOutcome.UPDATED, versioned.upsert(VersionedOrder.of("o1", "shipped"))));

        assertEquals(1, events.size());
        assertEquals(Set.of("status", "version"), events.get(0).getUpdateDescription().getUpdatedFields().keySet());
        assertEquals(2L, storedOrder("o1").get("version"));
        assertThrows(OptimisticLockingFailureException.class, () -> template.save(saved.withStatus("cancelled")));
    }

    @Test
    void primitiveVersionStartsAtOneAsWithSave() {
        Diffsert versioned = new Diffsert(template).withTypeKeyRemoved(false);

        versioned.upsert(new CountedOrder("o1", "open", 0));

        assertEquals(1, storedOrder("o1").get("version"));
        // for a primitive version, 0 means new: save() would insert and fail on the existing id
        CountedOrder saved = template.save(new CountedOrder("o1", "paid", template.findById("o1", CountedOrder.class)
                .version()));
        assertEquals(2, saved.version());
    }

    @Test
    void versionFieldMustMatchConfiguredOneAndBatchesMustAgree() {
        Diffsert configured = new Diffsert(template, DiffsertOptions.builder().versionField("revision").build());

        assertThrows(IllegalArgumentException.class, () -> configured.upsert(VersionedOrder.of("o1", "open")));
        assertThrows(IllegalArgumentException.class,
                () -> writer.upsertAll(List.of(VersionedOrder.of("o1", "open"), Order.of("o2", "open")), "orders"));
        assertEquals(0, orders.countDocuments());
    }

    // ---------------------------------------------------------------- helpers

    /** Like {@code @EnableMongoAuditing}'s handler, with the time and user taken from {@link #now} and {@link #auditor}. */
    private AuditingHandler auditingHandler() {
        IsNewAwareAuditingHandler handler = IsNewAwareAuditingHandler.from(template.getConverter().getMappingContext());
        handler.setDateTimeProvider(() -> Optional.of(now));
        handler.setAuditorAware(() -> Optional.of(auditor));
        return handler;
    }

    private Document storedOrder(String id) {
        return orders.find(new Document("_id", id)).first();
    }

    private Document stored(String id) {
        return collection.find(new Document("_id", id)).first();
    }

    private List<ChangeStreamDocument<Document>> eventsDuring(Runnable action) {
        return TestMongo.eventsDuring(collection, action);
    }
}
