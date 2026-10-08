package dev.jbaby.diffsert;

import java.util.List;

import org.bson.BsonValue;

/**
 * Result of a batch write with {@link DeltaMongoWriter}.
 *
 * @param requested   number of documents passed in
 * @param matched     documents that already existed
 * @param modified    existing documents that actually changed (one change event each)
 * @param inserted    documents that were newly inserted by an upsert (one insert event each)
 * @param insertedIds ids of the inserted documents
 */
public record BatchWriteResult(int requested, int matched, int modified, int inserted, List<BsonValue> insertedIds) {

    public BatchWriteResult {
        insertedIds = List.copyOf(insertedIds);
    }

    public static BatchWriteResult empty() {
        return new BatchWriteResult(0, 0, 0, 0, List.of());
    }

    /** Existing documents that were left untouched because nothing relevant changed. */
    public int unchanged() {
        return matched - modified;
    }

    /** Documents that didn't exist and were not inserted (update without upsert). */
    public int notFound() {
        return requested - matched - inserted;
    }

    public BatchWriteResult plus(BatchWriteResult other) {
        List<BsonValue> ids = new java.util.ArrayList<>(insertedIds);
        ids.addAll(other.insertedIds);
        return new BatchWriteResult(requested + other.requested, matched + other.matched,
                modified + other.modified, inserted + other.inserted, ids);
    }

    @Override
    public String toString() {
        return "requested=%d, inserted=%d, updated=%d, unchanged=%d, notFound=%d"
                .formatted(requested, inserted, modified, unchanged(), notFound());
    }
}
