package dev.jbaby.diffsert.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.bson.Document;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration;
import org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.mapping.Field;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import dev.jbaby.diffsert.DiffsertListener;
import dev.jbaby.diffsert.testsupport.TestMongo;
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

    @org.springframework.data.mongodb.core.mapping.Document("orders")
    record CustomerOrder(
            @Id String id,
            String status,
            @CreatedDate @Field("_createdAt") Instant createdAt,
            @LastModifiedDate @Field("_updatedAt") Instant updatedAt) {

        static CustomerOrder of(String id, String status) {
            return new CustomerOrder(id, status, null, null);
        }
    }

    static final Instant T1 = Instant.parse("2026-10-01T08:00:00Z");
    static final Instant T2 = Instant.parse("2026-10-02T08:00:00Z");

    @Configuration(proxyBeanMethods = false)
    @EnableMongoAuditing(dateTimeProviderRef = "clock")
    static class AuditingAppConfig {

        @Bean
        AtomicReference<Instant> now() {
            return new AtomicReference<>(T1);
        }

        @Bean
        DateTimeProvider clock(AtomicReference<Instant> now) {
            return () -> Optional.of(now.get());
        }
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

    @Test
    @SuppressWarnings("unchecked")
    void handlesAuditingWithBootsMongoSetup() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MongoAutoConfiguration.class,
                        DataMongoAutoConfiguration.class, DiffsertAutoConfiguration.class))
                .withUserConfiguration(AuditingAppConfig.class)
                .withPropertyValues("spring.mongodb.uri=" + MONGO.getReplicaSetUrl("test"))
                .run(context -> {
                    MongoTemplate template = context.getBean(MongoTemplate.class);
                    template.dropCollection(CustomerOrder.class);
                    Diffsert diffsert = context.getBean(Diffsert.class);
                    AtomicReference<Instant> now = context.getBean(AtomicReference.class);

                    assertThat(diffsert.upsert(CustomerOrder.of("o1", "open"))).isEqualTo(WriteOutcome.INSERTED);
                    now.set(T2);
                    assertThat(diffsert.upsert(CustomerOrder.of("o1", "open"))).isEqualTo(WriteOutcome.UNCHANGED);
                    assertThat(stored(template, "o1").get("_updatedAt")).isEqualTo(Date.from(T1));

                    assertThat(diffsert.upsert(CustomerOrder.of("o1", "shipped"))).isEqualTo(WriteOutcome.UPDATED);
                    Document stored = stored(template, "o1");
                    assertThat(stored.get("_createdAt")).isEqualTo(Date.from(T1));
                    assertThat(stored.get("_updatedAt")).isEqualTo(Date.from(T2));
                });
    }

    private static Document stored(MongoTemplate template, String id) {
        return template.getCollection("orders").find(new Document("_id", id)).first();
    }

    private static double count(MeterRegistry registry, String outcome) {
        return registry.get(DiffsertMetrics.METER_NAME)
                .tag("collection", "customers")
                .tag("outcome", outcome)
                .counter()
                .count();
    }
}
