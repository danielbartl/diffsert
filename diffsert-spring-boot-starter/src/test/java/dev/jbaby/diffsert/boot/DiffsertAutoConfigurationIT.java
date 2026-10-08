package dev.jbaby.diffsert.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import dev.jbaby.diffsert.DiffsertListener;
import dev.jbaby.diffsert.TestMongo;
import dev.jbaby.diffsert.WriteOutcome;
import dev.jbaby.diffsert.spring.Diffsert;

/** The auto-configured bean writes against a real server and reports to metrics and other listeners. */
@Testcontainers
class DiffsertAutoConfigurationIT {

    @Container
    static final MongoDBContainer MONGO = TestMongo.container();

    @org.springframework.data.mongodb.core.mapping.Document("customers")
    record Customer(@Id String id, String name, String jobRunId) {
    }

    @Configuration(proxyBeanMethods = false)
    static class AppConfig {

        @Bean(destroyMethod = "close")
        MongoClient mongoClient() {
            return MongoClients.create(MONGO.getReplicaSetUrl("test"));
        }

        @Bean
        MongoTemplate mongoTemplate(MongoClient client) {
            return new MongoTemplate(client, "test");
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        List<String> reported() {
            return new ArrayList<>();
        }

        @Bean
        DiffsertListener recordingListener(List<String> reported) {
            return (collectionName, result) -> reported.add(collectionName + ": " + result);
        }

        /** Runs first and always fails; must not affect the write, the caller or the other listeners. */
        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        DiffsertListener failingListener() {
            return (collectionName, result) -> {
                throw new IllegalStateException("listener failure");
            };
        }
    }

    @Test
    void writesAndReportsToMetricsAndListenersDespiteAFailingOne() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DiffsertAutoConfiguration.class))
                .withUserConfiguration(AppConfig.class)
                .withPropertyValues("diffsert.ignore-fields=jobRunId")
                .run(context -> {
                    context.getBean(MongoTemplate.class).dropCollection(Customer.class);
                    Diffsert diffsert = context.getBean(Diffsert.class);

                    assertThat(diffsert.upsert(new Customer("c1", "Acme", "run-1"))).isEqualTo(WriteOutcome.INSERTED);
                    assertThat(diffsert.upsert(new Customer("c1", "Acme", "run-2"))).isEqualTo(WriteOutcome.UNCHANGED);
                    assertThat(diffsert.upsert(new Customer("c1", "Acme Corp", "run-3"))).isEqualTo(WriteOutcome.UPDATED);

                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(count(registry, "inserted")).isEqualTo(1);
                    assertThat(count(registry, "unchanged")).isEqualTo(1);
                    assertThat(count(registry, "updated")).isEqualTo(1);
                    assertThat(context.getBean("reported", List.class)).hasSize(3);
                });
    }

    private static double count(MeterRegistry registry, String outcome) {
        return registry.get(DiffsertMetrics.METER_NAME)
                .tag("collection", "customers")
                .tag("outcome", outcome)
                .counter()
                .count();
    }
}
