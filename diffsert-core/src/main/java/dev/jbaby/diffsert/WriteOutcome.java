package dev.jbaby.diffsert;

/** Result of writing a single document with {@link DiffsertWriter}. */
public enum WriteOutcome {
    /** The document did not exist and was inserted (upsert only). */
    INSERTED,
    /** The document existed and at least one field changed; only the changed fields appear in the change event. */
    UPDATED,
    /** The document existed and nothing relevant changed; nothing was written and no change event was emitted. */
    UNCHANGED,
    /** The document did not exist and nothing was written (update without upsert). */
    NOT_FOUND
}
