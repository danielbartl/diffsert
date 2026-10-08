package dev.jbaby.diffsert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.mapping.Field;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.OperationType;

/**
 * Verifies the writer against a real MongoDB (single-node replica set, so change streams work) by looking at the
 * change events that each write produces.
 */
@Testcontainers
class DeltaMongoWriterIT {

    /**
     * Server image, overridable with {@code -Dmongo.image=mongo:8.0.32} to test other versions. Testcontainers 2
     * starts a standalone server unless asked for a replica set; change streams need one.
     */
    @Container
    static final MongoDBContainer MONGO =
            new MongoDBContainer(System.getProperty("mongo.image", "mongo:7.0.43")).withReplicaSet();

    static MongoClient client;
    static MongoTemplate template;

    MongoCollection<Document> collection;
    DeltaMongoWriter writer;

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

    static Customer customer(String id, String name, String city) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return new Customer(id, name, city, "run-1", now, now);
    }

    @BeforeAll
    static void connect() {
        client = MongoClients.create(MONGO.getReplicaSetUrl("test"));
        template = new MongoTemplate(client, "test");
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
        writer = new DeltaMongoWriter(template, DeltaWriteOptions.builder()
                .ignoreForChangeDetection("jobRunId", "_updatedAt")
                .preserveExistingValue("_createdAt")
                .build());
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
        DeltaMongoWriter merging = writer.withOptions(writer.options().toBuilder().merge().build());

        merging.upsert(customer("c1", "Acme", "Berlin"));

        assertEquals(42, stored("c1").getInteger("foreign").intValue());
        assertEquals("Berlin", stored("c1").getString("city"));
    }

    @Test
    void writerWithoutOptionsBehavesLikePlainDeltaReplace() {
        DeltaMongoWriter plain = new DeltaMongoWriter(template);
        Customer a = customer("c1", "Acme", "Berlin");
        plain.upsert(a);

        assertEquals(WriteOutcome.UNCHANGED, plain.upsert(a));
        assertEquals(WriteOutcome.UPDATED, plain.upsert(a.nextRun()));
    }

    // ---------------------------------------------------------------- helpers

    private Document stored(String id) {
        return collection.find(new Document("_id", id)).first();
    }

    /**
     * Collects the change events caused by {@code action}. A sentinel insert afterwards marks the end, so the method
     * also works (and returns an empty list) when the action produced no events at all.
     */
    private List<ChangeStreamDocument<Document>> eventsDuring(Runnable action) {
        String sentinel = "sentinel-" + UUID.randomUUID();
        try (var cursor = collection.watch().cursor()) {
            action.run();
            collection.insertOne(new Document("_id", sentinel));

            List<ChangeStreamDocument<Document>> events = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline) {
                ChangeStreamDocument<Document> ev = cursor.tryNext();
                if (ev == null) {
                    continue;
                }
                if (sentinel.equals(ev.getDocumentKey().getString("_id").getValue())) {
                    collection.deleteOne(new Document("_id", sentinel));
                    return events;
                }
                events.add(ev);
            }
            throw new AssertionError("sentinel change event not received within 10s");
        }
    }
}
