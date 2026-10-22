package dev.jbaby.diffsert;

/**
 * Called after every write of a {@link DiffsertWriter}, e.g. to count inserted, updated and unchanged documents.
 * Single writes are reported as a result with {@code requested == 1}.
 * <p>
 * If a bulk write fails with a {@link com.mongodb.MongoBulkWriteException}, the listener first receives the result of
 * the operations that succeeded ({@code requested} counts only those), then the exception is rethrown. A single
 * write that fails isn't reported, not even after a {@link com.mongodb.MongoWriteConcernException}: the write was
 * applied, but the driver doesn't tell whether it changed the document.
 * <p>
 * The listener runs on the writing thread after the write has completed. An exception thrown by the listener
 * propagates to the caller, although the write itself has already happened.
 */
@FunctionalInterface
public interface DiffsertListener {

    DiffsertListener NONE = (collectionName, result) -> {
    };

    void onWrite(String collectionName, BatchWriteResult result);
}
