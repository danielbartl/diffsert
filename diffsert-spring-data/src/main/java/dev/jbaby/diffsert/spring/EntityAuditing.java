package dev.jbaby.diffsert.spring;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.data.mapping.PersistentPropertyAccessor;
import org.springframework.data.mapping.PersistentPropertyPath;
import org.springframework.data.mapping.context.MappingContext;
import org.springframework.data.mongodb.core.mapping.MongoPersistentEntity;
import org.springframework.data.mongodb.core.mapping.MongoPersistentProperty;

import dev.jbaby.diffsert.DiffsertOptions;

/**
 * The properties of one entity type that carry Spring Data's auditing annotations. Only top-level properties are
 * supported, because Diffsert can only ignore or preserve top-level fields.
 */
record EntityAuditing(
        MongoPersistentEntity<?> entity,
        List<MongoPersistentProperty> created,
        List<MongoPersistentProperty> modified) {

    static final EntityAuditing NONE = new EntityAuditing(null, List.of(), List.of());

    static EntityAuditing of(MappingContext<? extends MongoPersistentEntity<?>, MongoPersistentProperty> context,
            Class<?> type) {
        if (Map.class.isAssignableFrom(type)) {
            return NONE;
        }
        MongoPersistentEntity<?> entity = context.getPersistentEntity(type);
        if (entity == null) {
            return NONE;
        }
        List<MongoPersistentProperty> created = topLevel(context, type, CreatedDate.class, CreatedBy.class);
        List<MongoPersistentProperty> modified = topLevel(context, type, LastModifiedDate.class, LastModifiedBy.class);
        return created.isEmpty() && modified.isEmpty() ? NONE : new EntityAuditing(entity, created, modified);
    }

    boolean isEmpty() {
        return created.isEmpty() && modified.isEmpty();
    }

    /** {@code options} plus the created fields as preserved and the modified fields as ignored. */
    DiffsertOptions applyTo(DiffsertOptions options) {
        if (isEmpty()) {
            return options;
        }
        return options.toBuilder()
                .preserveExistingValue(fieldNames(created))
                .ignoreForChangeDetection(fieldNames(modified))
                .build();
    }

    /**
     * Marks {@code bean} as created; whether the document is new is only known on the server, where preserved fields
     * keep their stored value. Also marks it as modified if the handler doesn't do that on creation
     * ({@code modifyOnCreate = false}), so a real change never removes the stored modification fields.
     */
    Object mark(AuditingHandler handler, Object bean) {
        Object marked = handler.markCreated(bean);
        PersistentPropertyAccessor<Object> accessor = entity.getPropertyAccessor(marked);
        boolean modificationMissing = modified.stream().allMatch(p -> accessor.getProperty(p) == null);
        return !modified.isEmpty() && modificationMissing ? handler.markModified(marked) : marked;
    }

    @SafeVarargs
    private static List<MongoPersistentProperty> topLevel(
            MappingContext<? extends MongoPersistentEntity<?>, MongoPersistentProperty> context, Class<?> type,
            Class<? extends Annotation>... annotations) {
        List<MongoPersistentProperty> properties = new ArrayList<>();
        for (PersistentPropertyPath<MongoPersistentProperty> path : context.findPersistentPropertyPaths(type,
                p -> List.of(annotations).stream().anyMatch(p::isAnnotationPresent))) {
            if (path.getLength() > 1) {
                throw new IllegalArgumentException("auditing field '" + path.toDotPath() + "' of " + type.getName()
                        + " is nested; Diffsert supports auditing annotations on top-level properties only");
            }
            properties.add(path.getLeafProperty());
        }
        return properties;
    }

    private static String[] fieldNames(List<MongoPersistentProperty> properties) {
        return properties.stream().map(MongoPersistentProperty::getFieldName).toArray(String[]::new);
    }
}
