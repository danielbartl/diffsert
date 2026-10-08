package dev.jbaby.diffsert.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import dev.jbaby.diffsert.BatchWriteResult;
import dev.jbaby.diffsert.DiffsertListener;
import dev.jbaby.diffsert.DiffsertOptions;
import dev.jbaby.diffsert.spring.Diffsert;

/** Bean wiring and property binding; no server needed (the client never connects). */
class DiffsertAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DiffsertAutoConfiguration.class));

    @Configuration(proxyBeanMethods = false)
    static class MongoConfig {

        @Bean(destroyMethod = "close")
        MongoClient mongoClient() {
            return MongoClients.create("mongodb://localhost:1");
        }

        @Bean
        MongoTemplate mongoTemplate(MongoClient client) {
            return new MongoTemplate(client, "test");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MeterRegistryConfig {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Test
    void createsDiffsertWithDefaults() {
        runner.withUserConfiguration(MongoConfig.class).run(context -> {
            Diffsert diffsert = context.getBean(Diffsert.class);
            assertThat(diffsert.options()).isEqualTo(DiffsertOptions.defaults());
            assertThat(diffsert.removesTypeKey()).isTrue();
        });
    }

    @Test
    void bindsProperties() {
        runner.withUserConfiguration(MongoConfig.class)
                .withPropertyValues(
                        "diffsert.mode=merge",
                        "diffsert.ignore-fields=jobRunId,_updatedAt",
                        "diffsert.preserve-fields=_createdAt",
                        "diffsert.ordered=true",
                        "diffsert.remove-type-key=false")
                .run(context -> {
                    Diffsert diffsert = context.getBean(Diffsert.class);
                    DiffsertOptions options = diffsert.options();
                    assertThat(options.mode()).isEqualTo(DiffsertOptions.Mode.MERGE);
                    assertThat(options.ignoredFields()).containsExactly("jobRunId", "_updatedAt");
                    assertThat(options.preservedFields()).isEqualTo(Set.of("_createdAt"));
                    assertThat(options.ordered()).isTrue();
                    assertThat(diffsert.removesTypeKey()).isFalse();
                });
    }

    @Test
    void optionsBeanWinsOverProperties() {
        DiffsertOptions custom = DiffsertOptions.builder().merge().ignoreForChangeDetection("syncedAt").build();
        runner.withUserConfiguration(MongoConfig.class)
                .withBean(DiffsertOptions.class, () -> custom)
                .withPropertyValues("diffsert.ignore-fields=jobRunId")
                .run(context -> assertThat(context.getBean(Diffsert.class).options()).isEqualTo(custom));
    }

    @Test
    void isolatedListenersSurviveAFailingOne() {
        List<String> reported = new ArrayList<>();
        DiffsertListener failing = (collectionName, result) -> {
            throw new IllegalStateException("boom");
        };
        DiffsertListener recording = (collectionName, result) -> reported.add(collectionName);

        DiffsertAutoConfiguration.isolated(List.of(failing, recording))
                .onWrite("customers", BatchWriteResult.empty());

        assertThat(reported).containsExactly("customers");
    }

    @Test
    void failsOnUnsupportedFieldName() {
        runner.withUserConfiguration(MongoConfig.class)
                .withPropertyValues("diffsert.ignore-fields=meta.runId")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("meta.runId"));
    }

    @Test
    void noDiffsertWithoutMongoOperations() {
        runner.run(context -> assertThat(context).doesNotHaveBean(Diffsert.class));
    }

    @Test
    void backsOffWhenApplicationDefinesDiffsert() {
        runner.withUserConfiguration(MongoConfig.class)
                .withBean("custom", Diffsert.class, () -> new Diffsert(new MongoTemplate(
                        MongoClients.create("mongodb://localhost:1"), "other")))
                .run(context -> assertThat(context).getBean(Diffsert.class)
                        .isSameAs(context.getBean("custom")));
    }

    @Test
    void registersMetricsWithMeterRegistry() {
        runner.withUserConfiguration(MongoConfig.class, MeterRegistryConfig.class)
                .run(context -> assertThat(context).hasSingleBean(DiffsertMetrics.class));
    }

    @Test
    void noMetricsWithoutMeterRegistry() {
        runner.withUserConfiguration(MongoConfig.class)
                .run(context -> assertThat(context).hasSingleBean(Diffsert.class)
                        .doesNotHaveBean(DiffsertMetrics.class));
    }

    @Test
    void noMetricsWithoutMicrometer() {
        runner.withUserConfiguration(MongoConfig.class)
                .withClassLoader(new FilteredClassLoader(MeterRegistry.class))
                .run(context -> assertThat(context).hasSingleBean(Diffsert.class)
                        .doesNotHaveBean(DiffsertMetrics.class));
    }

    @Test
    void metricsCountDocumentsPerOutcome() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DiffsertMetrics metrics = new DiffsertMetrics(registry);

        metrics.onWrite("customers", new BatchWriteResult(1000, 990, 12, 3, List.of()));
        metrics.onWrite("customers", new BatchWriteResult(1, 1, 0, 0, List.of()));

        assertThat(count(registry, "inserted")).isEqualTo(3);
        assertThat(count(registry, "updated")).isEqualTo(12);
        assertThat(count(registry, "unchanged")).isEqualTo(979);
        assertThat(count(registry, "not_found")).isEqualTo(7);
    }

    private static double count(MeterRegistry registry, String outcome) {
        return registry.get(DiffsertMetrics.METER_NAME)
                .tag("collection", "customers")
                .tag("outcome", outcome)
                .counter()
                .count();
    }
}
