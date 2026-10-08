package dev.jbaby.diffsert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import com.mongodb.MongoBulkWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.OperationType;

/**
 * Verifies the mechanism with plain driver documents against a real MongoDB (single-node replica set, so change
 * streams work) by looking at the change events that each write produces.
 */
@Testcontainers
class DiffsertWriterIT {

    @Container
    static final MongoDBContainer MONGO = TestMongo.container();

    static MongoClient client;

    MongoCollection<Document> collection;
    DiffsertWriter writer;

    static Document customer(String id, String name, String city) {
        Date now = new Date();
        return new Document("_id", id)
                .append("name", name)
                .append("city", city)
                .append("jobRunId", "run-1")
                .append("_createdAt", now)
                .append("_updatedAt", now);
    }

    /** Same business data, metadata of a new batch run. */
    static Document nextRun(Document doc) {
        Date later = new Date(System.currentTimeMillis() + 60_000);
        return new Document(doc)
                .append("jobRunId", UUID.randomUUID().toString())
                .append("_createdAt", later)
                .append("_updatedAt", later);
    }

    static Document with(Document doc, String field, Object value) {
        return new Document(doc).append(field, value);
    }

    @BeforeAll
    static void connect() {
        client = MongoClients.create(MONGO.getReplicaSetUrl("test"));
    }

    @AfterAll
    static void disconnect() {
        client.close();
    }

    @BeforeEach
    void setUp() {
        var db = client.getDatabase("test");
        db.getCollection("customers").drop();
        db.createCollection("customers");
        collection = db.getCollection("customers");
        writer = new DiffsertWriter(DiffsertOptions.builder()
                .ignoreForChangeDetection("jobRunId", "_updatedAt")
                .preserveExistingValue("_createdAt")
                .build());
    }

    @Test
    void insertsNewDocument() {
        Document a = customer("c1", "Acme", "Berlin");

        var events = eventsDuring(() -> assertEquals(WriteOutcome.INSERTED, writer.upsert(collection, a)));

        assertEquals(1, events.size());
        assertEquals(OperationType.INSERT, events.get(0).getOperationType());
        assertEquals(a, stored("c1"));
    }

    @Test
    void doesNothingWhenOnlyIgnoredFieldsDiffer() {
        Document a = customer("c1", "Acme", "Berlin");
        writer.upsert(collection, a);

        var events = eventsDuring(() -> assertEquals(WriteOutcome.UNCHANGED, writer.upsert(collection, nextRun(a))));

        assertEquals(List.of(), events);
        assertEquals("run-1", stored("c1").getString("jobRunId"), "unchanged document keeps old run metadata");
    }

    @Test
    void reportsOnlyChangedFieldsAndKeepsCreatedAt() {
        Document a = customer("c1", "Acme", "Berlin");
        writer.upsert(collection, a);
        Document before = stored("c1");

        var events = eventsDuring(() ->
                assertEquals(WriteOutcome.UPDATED, writer.upsert(collection, with(nextRun(a), "city", "Hamburg"))));

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
        Document a = customer("c1", "Acme", "Berlin");
        writer.upsert(collection, a);
        Document withoutName = new Document(a);
        withoutName.remove("name");

        var events = eventsDuring(() -> writer.upsert(collection, withoutName));

        assertEquals(1, events.size());
        assertEquals(List.of("name"), events.get(0).getUpdateDescription().getRemovedFields());
    }

    @Test
    void batchWritesOnlyNewAndChangedDocuments() {
        Document unchanged = customer("c1", "Acme", "Berlin");
        Document changed = customer("c2", "Globex", "Munich");
        writer.upsertAll(collection, List.of(unchanged, changed));

        List<Document> batch = List.of(
                nextRun(unchanged),
                with(nextRun(changed), "city", "Cologne"),
                customer("c3", "Initech", "Frankfurt"));

        BatchWriteResult[] result = new BatchWriteResult[1];
        var events = eventsDuring(() -> result[0] = writer.upsertAll(collection, batch));

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
        assertEquals(WriteOutcome.NOT_FOUND, writer.update(collection, customer("missing", "X", "Y")));
        assertEquals(0, collection.countDocuments());

        BatchWriteResult r = writer.updateAll(collection, List.of(customer("m1", "X", "Y")));
        assertEquals(1, r.notFound());
        assertEquals(0, collection.countDocuments());
    }

    @Test
    void mergeModeKeepsFieldsWrittenByOthers() {
        collection.insertOne(new Document("_id", "c1").append("name", "Acme").append("foreign", 42));
        DiffsertWriter merging = writer.withOptions(writer.options().toBuilder().merge().build());

        merging.upsert(collection, customer("c1", "Acme", "Berlin"));

        assertEquals(42, stored("c1").getInteger("foreign").intValue());
        assertEquals("Berlin", stored("c1").getString("city"));
    }

    @Test
    void writerWithoutOptionsBehavesLikePlainDeltaReplace() {
        DiffsertWriter plain = new DiffsertWriter();
        Document a = customer("c1", "Acme", "Berlin");
        plain.upsert(collection, a);

        assertEquals(WriteOutcome.UNCHANGED, plain.upsert(collection, a));
        assertEquals(WriteOutcome.UPDATED, plain.upsert(collection, nextRun(a)));
    }

    // The following tests pin down server behavior that the README documents as caveats.

    @Test
    void reportsArrayAndNestedChangesByPath() {
        Document a = customer("c1", "Acme", "Berlin")
                .append("tags", List.of("a", "b", "c", "d", "e", "f", "g", "h"))
                .append("address", new Document("street", "Main").append("zip", "10115"));
        writer.upsert(collection, a);
        Document changed = with(with(a, "tags", List.of("a", "b", "X", "d", "e", "f", "g", "h")),
                "address", new Document("street", "Side").append("zip", "10115"));

        var events = eventsDuring(() -> writer.upsert(collection, changed));

        assertEquals(1, events.size());
        assertEquals(Set.of("tags.2", "address.street"), events.get(0).getUpdateDescription().getUpdatedFields().keySet());
    }

    @Test
    void reportsShortenedArrayAsTruncated() {
        Document a = customer("c1", "Acme", "Berlin").append("tags", List.of("a", "b", "c", "d", "e", "f", "g", "h"));
        writer.upsert(collection, a);

        var events = eventsDuring(() -> writer.upsert(collection, with(a, "tags", List.of("a", "b", "c"))));

        assertEquals(1, events.size());
        var truncated = events.get(0).getUpdateDescription().getTruncatedArrays();
        assertEquals(1, truncated.size());
        assertEquals("tags", truncated.get(0).getField());
        assertEquals(3, truncated.get(0).getNewSize());
    }

    @Test
    void smallDocumentChangeIsReportedAsReplace() {
        // The server logs a full replacement when the delta would not be smaller than the document.
        DiffsertWriter plain = new DiffsertWriter();
        plain.upsert(collection, new Document("_id", "c1").append("a", "x"));

        var events = eventsDuring(() -> assertEquals(WriteOutcome.UPDATED,
                plain.upsert(collection, new Document("_id", "c1").append("a", "y"))));

        assertEquals(1, events.size());
        assertEquals(OperationType.REPLACE, events.get(0).getOperationType());
    }

    @Test
    void numericTypeChangeAloneIsWrittenOnlyWithoutIgnoredOrPreservedFields() {
        Document a = customer("c1", "Acme", "Berlin").append("count", 1);
        writer.upsert(collection, a);

        // With ignored/preserved fields, the comparison is $eq, which treats 1 and 1L as equal.
        assertEquals(WriteOutcome.UNCHANGED, writer.upsert(collection, with(a, "count", 1L)));
        assertEquals(Integer.class, stored("c1").get("count").getClass());

        assertEquals(WriteOutcome.UPDATED, new DiffsertWriter().upsert(collection, with(a, "count", 1L)));
        assertEquals(Long.class, stored("c1").get("count").getClass());
    }

    @Test
    void differentFieldOrderCountsAsChange() {
        Document a = customer("c1", "Acme", "Berlin");
        writer.upsert(collection, a);
        Document reordered = new Document("_id", "c1");
        a.entrySet().stream().filter(e -> !e.getKey().equals("_id")).toList().reversed()
                .forEach(e -> reordered.append(e.getKey(), e.getValue()));

        assertEquals(WriteOutcome.UPDATED, writer.upsert(collection, reordered));
    }

    @Test
    void writesDollarValuesLiterally() {
        Document a = customer("c1", "$name", "$$ROOT");

        writer.upsert(collection, a);

        assertEquals(a, stored("c1"));
    }

    @Test
    void rejectsDocumentWithoutId() {
        assertThrows(IllegalArgumentException.class, () -> writer.upsert(collection, new Document("name", "Acme")));
    }

    @Test
    void reportsWritesToListener() {
        List<String> reported = new ArrayList<>();
        DiffsertWriter listening = writer.withListener((name, result) -> reported.add(name + ": " + result));
        Document a = customer("c1", "Acme", "Berlin");
        Document moved = with(a, "city", "Hamburg");

        listening.upsert(collection, a);
        listening.update(collection, moved);
        listening.update(collection, customer("missing", "X", "Y"));
        listening.upsertAll(collection, List.of(nextRun(moved), customer("c2", "Globex", "Munich")));
        listening.upsertAll(collection, List.of());

        assertEquals(List.of(
                "customers: requested=1, inserted=1, updated=0, unchanged=0, notFound=0",
                "customers: requested=1, inserted=0, updated=1, unchanged=0, notFound=0",
                "customers: requested=1, inserted=0, updated=0, unchanged=0, notFound=1",
                "customers: requested=2, inserted=1, updated=0, unchanged=1, notFound=0"), reported);
    }

    @Test
    void reportsSucceededWritesOfFailedUnorderedBatch() {
        List<String> reported = new ArrayList<>();
        DiffsertWriter listening = writer.withListener((name, result) -> reported.add(name + ": " + result));

        assertThrows(MongoBulkWriteException.class, () -> listening.upsertAll(collection, batchWithDuplicateName()));

        assertEquals(List.of("customers: requested=2, inserted=2, updated=0, unchanged=0, notFound=0"), reported);
        assertEquals(2, collection.countDocuments());
    }

    @Test
    void reportsSucceededWritesOfFailedOrderedBatch() {
        List<String> reported = new ArrayList<>();
        DiffsertWriter listening = writer
                .withOptions(writer.options().toBuilder().ordered(true).build())
                .withListener((name, result) -> reported.add(name + ": " + result));

        assertThrows(MongoBulkWriteException.class, () -> listening.upsertAll(collection, batchWithDuplicateName()));

        assertEquals(List.of("customers: requested=1, inserted=1, updated=0, unchanged=0, notFound=0"), reported);
        assertEquals(1, collection.countDocuments());
    }

    /** Three new documents; the second violates a unique index on {@code name}. */
    private List<Document> batchWithDuplicateName() {
        collection.createIndex(Indexes.ascending("name"), new IndexOptions().unique(true));
        return List.of(
                customer("c1", "Acme", "Berlin"),
                customer("c2", "Acme", "Munich"),
                customer("c3", "Initech", "Frankfurt"));
    }

    // ---------------------------------------------------------------- helpers

    private Document stored(String id) {
        return collection.find(new Document("_id", id)).first();
    }

    private List<ChangeStreamDocument<Document>> eventsDuring(Runnable action) {
        return TestMongo.eventsDuring(collection, action);
    }
}
