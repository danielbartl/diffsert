package dev.jbaby.diffsert.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

import dev.jbaby.diffsert.BatchWriteResult;
import dev.jbaby.diffsert.DiffsertOptions;
import dev.jbaby.diffsert.TestMongo;
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

    MongoCollection<Document> collection;
    Diffsert writer;

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
        writer = new Diffsert(template, DiffsertOptions.builder()
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

    // ---------------------------------------------------------------- helpers

    private Document stored(String id) {
        return collection.find(new Document("_id", id)).first();
    }

    private List<ChangeStreamDocument<Document>> eventsDuring(Runnable action) {
        return TestMongo.eventsDuring(collection, action);
    }
}
