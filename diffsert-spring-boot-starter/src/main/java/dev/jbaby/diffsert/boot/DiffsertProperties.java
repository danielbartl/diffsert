package dev.jbaby.diffsert.boot;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import dev.jbaby.diffsert.DiffsertOptions;

/** Configuration of the auto-configured {@link dev.jbaby.diffsert.spring.Diffsert} bean. */
@ConfigurationProperties("diffsert")
public class DiffsertProperties {

    /** How the new document is combined with the stored one. */
    private DiffsertOptions.Mode mode = DiffsertOptions.Mode.REPLACE;

    /**
     * Top-level fields that alone don't count as a change (e.g. jobRunId, _updatedAt). If only these differ,
     * nothing is written and no change event is emitted.
     */
    private List<String> ignoreFields = new ArrayList<>();

    /** Top-level fields whose stored value is kept once set (e.g. _createdAt). */
    private List<String> preserveFields = new ArrayList<>();

    /** Whether bulk writes are ordered (stop at the first failure) or unordered (faster, continue). */
    private boolean ordered = false;

    /** Whether Spring Data's type key (usually _class) is removed from converted entities. */
    private boolean removeTypeKey = true;

    public DiffsertOptions toOptions() {
        return DiffsertOptions.builder()
                .mode(mode)
                .ignoreForChangeDetection(ignoreFields.toArray(String[]::new))
                .preserveExistingValue(preserveFields.toArray(String[]::new))
                .ordered(ordered)
                .build();
    }

    public DiffsertOptions.Mode getMode() {
        return mode;
    }

    public void setMode(DiffsertOptions.Mode mode) {
        this.mode = mode;
    }

    public List<String> getIgnoreFields() {
        return ignoreFields;
    }

    public void setIgnoreFields(List<String> ignoreFields) {
        this.ignoreFields = ignoreFields;
    }

    public List<String> getPreserveFields() {
        return preserveFields;
    }

    public void setPreserveFields(List<String> preserveFields) {
        this.preserveFields = preserveFields;
    }

    public boolean isOrdered() {
        return ordered;
    }

    public void setOrdered(boolean ordered) {
        this.ordered = ordered;
    }

    public boolean isRemoveTypeKey() {
        return removeTypeKey;
    }

    public void setRemoveTypeKey(boolean removeTypeKey) {
        this.removeTypeKey = removeTypeKey;
    }
}
