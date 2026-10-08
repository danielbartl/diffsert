# Diffsert

Change-aware upserts for MongoDB and Spring Data MongoDB. Only fields that really changed reach the oplog, so change
streams, Debezium and other CDC consumers see real changes instead of a full replace on every write.

> Status: early work in progress, not yet released.

## Modules

| Artifact (`dev.jbaby`)         | Use it when                                                                 |
|--------------------------------|-----------------------------------------------------------------------------|
| `diffsert-spring-boot-starter` | Spring Boot app: a `Diffsert` bean from `diffsert.*` properties + metrics   |
| `diffsert-spring-data`         | Spring Data MongoDB without Boot auto-configuration (entities via `MongoConverter`) |
| `diffsert-core`                | Plain MongoDB Java driver, `Document` in, no Spring                         |

## Usage

### Spring Boot

```yaml
diffsert:
  ignore-fields: jobRunId, _updatedAt   # alone, these are not a change
  preserve-fields: _createdAt             # stored value wins once set
```

```java
@Autowired Diffsert diffsert;

BatchWriteResult result = diffsert.upsertAll(dtos, CustomerDto.class);
// requested=1000, inserted=3, updated=12, unchanged=985, notFound=0

WriteOutcome outcome = diffsert.upsert(dto);   // INSERTED / UPDATED / UNCHANGED / NOT_FOUND
```

Further properties: `diffsert.mode` (`replace` or `merge`), `diffsert.ordered`, `diffsert.remove-type-key`.
With Micrometer, written documents are counted as `diffsert.documents{collection, outcome}`. Every
`DiffsertListener` bean is notified of each write; a failing listener is logged and doesn't affect the write.

To configure options in code, define a `DiffsertOptions` bean; it takes precedence over the properties. Defining
your own `Diffsert` bean replaces the auto-configured one *including* listener and metrics wiring; attach them
yourself with `diffsert.withListener(diffsertMetrics)` if needed.

### Spring Data

```java
Diffsert diffsert = new Diffsert(mongoTemplate, DiffsertOptions.builder()
        .ignoreForChangeDetection("jobRunId", "_updatedAt")
        .preserveExistingValue("_createdAt")
        .build());

diffsert.upsertAll(dtos, CustomerDto.class);
```

### Plain driver

```java
DiffsertWriter writer = new DiffsertWriter(DiffsertOptions.builder()
        .ignoreForChangeDetection("jobRunId", "_updatedAt")
        .build());

writer.upsertAll(database.getCollection("customers"), documents);
```

## Requirements

Java 25, MongoDB 5.0 or later (tested up to 9.0). Change streams require a replica set. The Spring modules are built
against Spring Boot 4.0 / Spring Data 2025.1.

## License

[MIT](LICENSE)
