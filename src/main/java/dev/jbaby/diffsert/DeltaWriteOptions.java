package dev.jbaby.diffsert;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Immutable configuration for a {@link DeltaMongoWriter}.
 *
 * @param mode            how the new document is combined with the stored one
 * @param ignoredFields   top-level fields excluded from change detection (e.g. {@code jobRunId}, {@code _updatedAt}).
 *                        If only these differ, the write is a no-op: nothing is written and no change event is emitted.
 *                        If anything else differs, they are written along with the real changes.
 * @param preservedFields top-level fields whose stored value is kept when the document already has one
 *                        (e.g. {@code _createdAt}). They don't need to be listed as ignored as well.
 * @param removeTypeKey   strip Spring Data's type key (usually {@code _class}) from the converted document
 * @param ordered         ordered or unordered bulk writes (unordered is faster and continues after a failed operation)
 */
public record DeltaWriteOptions(
        Mode mode,
        Set<String> ignoredFields,
        Set<String> preservedFields,
        boolean removeTypeKey,
        boolean ordered) {

    public enum Mode {
        /** The new document becomes the full new state; fields missing from it are removed from the stored document. */
        REPLACE,
        /** Fields of the new document are set or added; fields only present in the stored document are kept. */
        MERGE
    }

    public DeltaWriteOptions {
        if (mode == null) {
            throw new IllegalArgumentException("mode must not be null");
        }
        ignoredFields = validated(ignoredFields, "ignoredFields");
        preservedFields = validated(preservedFields, "preservedFields");
    }

    /** REPLACE mode, no ignored or preserved fields, {@code _class} removed, unordered bulk writes. */
    public static DeltaWriteOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.mode = mode;
        b.ignoredFields.addAll(ignoredFields);
        b.preservedFields.addAll(preservedFields);
        b.removeTypeKey = removeTypeKey;
        b.ordered = ordered;
        return b;
    }

    private static Set<String> validated(Set<String> fields, String name) {
        if (fields == null) {
            return Set.of();
        }
        for (String f : fields) {
            if (f == null || f.isBlank() || f.contains(".") || f.startsWith("$") || f.equals("_id")) {
                throw new IllegalArgumentException(
                        name + ": only top-level field names other than _id are supported, got '" + f + "'");
            }
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(fields));
    }

    public static final class Builder {
        private Mode mode = Mode.REPLACE;
        private final Set<String> ignoredFields = new LinkedHashSet<>();
        private final Set<String> preservedFields = new LinkedHashSet<>();
        private boolean removeTypeKey = true;
        private boolean ordered = false;

        private Builder() {
        }

        public Builder mode(Mode mode) {
            this.mode = mode;
            return this;
        }

        public Builder replace() {
            return mode(Mode.REPLACE);
        }

        public Builder merge() {
            return mode(Mode.MERGE);
        }

        /** Fields that alone don't count as a change (see {@link DeltaWriteOptions#ignoredFields()}). */
        public Builder ignoreForChangeDetection(String... fields) {
            ignoredFields.addAll(List.of(fields));
            return this;
        }

        /** Fields whose stored value wins once set (see {@link DeltaWriteOptions#preservedFields()}). */
        public Builder preserveExistingValue(String... fields) {
            preservedFields.addAll(List.of(fields));
            return this;
        }

        public Builder removeTypeKey(boolean removeTypeKey) {
            this.removeTypeKey = removeTypeKey;
            return this;
        }

        public Builder ordered(boolean ordered) {
            this.ordered = ordered;
            return this;
        }

        public DeltaWriteOptions build() {
            return new DeltaWriteOptions(mode, ignoredFields, preservedFields, removeTypeKey, ordered);
        }
    }
}
