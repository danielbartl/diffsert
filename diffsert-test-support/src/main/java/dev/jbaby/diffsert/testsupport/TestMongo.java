package dev.jbaby.diffsert.testsupport;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bson.BsonString;
import org.bson.Document;
import org.testcontainers.mongodb.MongoDBContainer;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.changestream.ChangeStreamDocument;

/** Container setup and change event collection shared by the integration tests of all modules. */
public final class TestMongo {

    private TestMongo() {
    }

    /**
     * Server image, overridable with {@code -Dmongo.image=mongo:8.0.32} to test other versions. Testcontainers 2
     * starts a standalone server unless asked for a replica set; change streams need one.
     */
    public static MongoDBContainer container() {
        return new MongoDBContainer(System.getProperty("mongo.image", "mongo:7.0.43")).withReplicaSet();
    }

    /**
     * Collects the change events caused by {@code action}. A sentinel insert afterwards marks the end, so the method
     * also works (and returns an empty list) when the action produced no events at all.
     */
    public static List<ChangeStreamDocument<Document>> eventsDuring(MongoCollection<Document> collection,
            Runnable action) {
        BsonString sentinel = new BsonString("sentinel-" + UUID.randomUUID());
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
                if (sentinel.equals(ev.getDocumentKey().get("_id"))) {
                    collection.deleteOne(new Document("_id", sentinel));
                    return events;
                }
                events.add(ev);
            }
            throw new AssertionError("sentinel change event not received within 10s");
        }
    }
}
