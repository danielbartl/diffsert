package dev.jbaby.diffsert.boot;

import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.data.mongodb.core.MongoOperations;

import io.micrometer.core.instrument.MeterRegistry;

import dev.jbaby.diffsert.DiffsertListener;
import dev.jbaby.diffsert.DiffsertOptions;
import dev.jbaby.diffsert.spring.Diffsert;

/**
 * Provides a {@link Diffsert} bean using the application's {@link MongoOperations}, if it has a single one (or one
 * marked {@code @Primary}). Its options come from a {@link DiffsertOptions} bean if the application defines exactly
 * one (or one marked {@code @Primary}), otherwise from {@code diffsert.*} properties.
 * <p>
 * With {@code @EnableMongoAuditing}, its auditing handler is used for Spring Data's auditing annotations, see
 * {@link Diffsert#withAuditing(AuditingHandler)}.
 * <p>
 * All {@link DiffsertListener} beans are notified of every write; with Micrometer and a {@link MeterRegistry}
 * present, {@link DiffsertMetrics} is one of them. A failing listener is logged and doesn't affect the write, the
 * caller or the other listeners.
 * <p>
 * An application that defines its own {@code Diffsert} bean replaces this one, including the listener wiring; to
 * keep metrics, attach them itself ({@code diffsert.withListener(diffsertMetrics)}), or define a
 * {@code DiffsertOptions} bean instead.
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration",
        "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration" })
@ConditionalOnClass({ Diffsert.class, MongoOperations.class })
@EnableConfigurationProperties(DiffsertProperties.class)
public class DiffsertAutoConfiguration {

    private static final Log log = LogFactory.getLog(DiffsertAutoConfiguration.class);

    /** Registered by {@code @EnableMongoAuditing}; JPA auditing in the same application registers another handler. */
    static final String MONGO_AUDITING_HANDLER = "mongoAuditingHandler";

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnSingleCandidate(MongoOperations.class)
    public Diffsert diffsert(MongoOperations mongoOperations, DiffsertProperties properties,
            ObjectProvider<DiffsertOptions> options, ObjectProvider<DiffsertListener> listeners,
            @Qualifier(MONGO_AUDITING_HANDLER) ObjectProvider<AuditingHandler> auditingHandler) {
        Diffsert diffsert = new Diffsert(mongoOperations, options.getIfUnique(properties::toOptions))
                .withTypeKeyRemoved(properties.isRemoveTypeKey());
        AuditingHandler handler = auditingHandler.getIfAvailable();
        if (handler != null) {
            diffsert = diffsert.withAuditing(handler);
        }
        List<DiffsertListener> all = listeners.orderedStream().toList();
        return all.isEmpty() ? diffsert : diffsert.withListener(isolated(all));
    }

    /** Notifies every listener; a failing one is logged and doesn't stop the others or reach the caller. */
    static DiffsertListener isolated(List<DiffsertListener> listeners) {
        return (collectionName, result) -> {
            for (DiffsertListener listener : listeners) {
                try {
                    listener.onWrite(collectionName, result);
                } catch (RuntimeException e) {
                    log.warn("DiffsertListener " + listener + " failed for " + collectionName + " (" + result + ")",
                            e);
                }
            }
        };
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterRegistry.class)
    static class DiffsertMetricsConfiguration {

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnBean(MeterRegistry.class)
        DiffsertMetrics diffsertMetrics(MeterRegistry meterRegistry) {
            return new DiffsertMetrics(meterRegistry);
        }
    }
}
