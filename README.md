# Diffsert

[![CI](https://github.com/danielbartl/diffsert/actions/workflows/ci.yml/badge.svg)](https://github.com/danielbartl/diffsert/actions/workflows/ci.yml)

**Website:** https://danielbartl.github.io/diffsert/

Change-aware upserts for MongoDB and Spring Data MongoDB. Only fields that really changed reach the oplog, so change
streams, Debezium and other CDC consumers see real changes instead of a full replace on every write. Unchanged
documents cause no write and no event at all.

> Status: early work in progress, not yet released to Maven Central.

## The problem

A typical sync job copies data from another system into MongoDB, for example 1000 mapped DTOs per batch, every run,
with `save()` or `replaceOne`. Every one of those writes is a full replacement: the oplog records the whole document
and change streams emit a `replace` event with the full document, whether anything changed or not. Consumers that
should react to specific changes (via Debezium or a change stream) see every document as changed on every run.

Run metadata makes it worse: fields like `jobRunId` or `_updatedAt` differ on every run, so even a write that only
happens "if something changed" would still write every document.

## What Diffsert does

| Situation                                  | `save()` / `replaceOne`        | Diffsert                                          |
|--------------------------------------------|--------------------------------|---------------------------------------------------|
| Document is new                            | `insert` event                 | `insert` event                                    |
| Nothing changed                            | `replace` event, full document | no write, no event                                |
| Only run metadata changed (ignored fields) | `replace` event, full document | no write, no event                                |
| `city` changed                             | `replace` event, full document | `update` event: `city` + the ignored fields       |
| A field was removed                        | `replace` event, full document | `update` event with `removedFields: ["name"]`     |
| `_createdAt` in the new document           | overwritten                    | stored value kept (preserved field)               |

For the `city` change, the change event's `updateDescription` looks like this:

```json
{ "updatedFields": { "city": "Hamburg", "jobRunId": "run-2", "_updatedAt": { "$date": "2026-10-08T12:00:00Z" } },
  "removedFields": [], "truncatedArrays": [] }
```

## How it works

Every write is an `updateOne` by `_id` with an aggregation pipeline instead of a replacement:

```js
db.customers.updateOne({ _id: "c1" }, [{ $replaceWith: { $literal: <new document> } }], { upsert: true })
```

The result is the same document as with a replace, but MongoDB 5.0+ compares the result with the stored document and
writes only the difference to the oplog. Change streams then report an `update` event with just the changed fields,
and an identical result is a no-op. `$literal` ensures values starting with `$` are never treated as expressions.

With ignored or preserved fields configured, the pipeline also compares the new document with the stored one while
leaving out the ignored fields, and keeps the stored document unchanged if nothing else differs. Preserved fields are
copied back from the stored document. The exact pipeline is documented in `DiffsertWriter.buildPipeline`.

## Requirements and supported versions

- Java 25.
- MongoDB 5.0 or later. Change streams (and therefore Debezium) require a replica set; the writes themselves also
  work on a standalone server.
- The delta oplog behavior Diffsert relies on is a server implementation detail, not a documented guarantee. CI runs
  all tests against MongoDB **5.0, 6.0, 7.0, 8.0, 8.2, 8.3 and 9.0**, on every change and weekly.
- Spring modules: built against Spring Boot 4.0 / Spring Data MongoDB 2025.1, MongoDB Java driver 5.6.

## Modules

| Artifact (`dev.jbaby`)         | Use it when                                                                          |
|--------------------------------|--------------------------------------------------------------------------------------|
| `diffsert-spring-boot-starter` | Spring Boot app: a `Diffsert` bean from `diffsert.*` properties, plus metrics       |
| `diffsert-spring-data`         | Spring Data MongoDB without Boot auto-configuration (entities via `MongoConverter`) |
| `diffsert-core`                | Plain MongoDB Java driver, `Document` in, no Spring                                  |

Until the first release, build and install locally with `mvn install` and use version `0.1.0-SNAPSHOT`.

## Usage

### Spring Boot

```xml
<dependency>
    <groupId>dev.jbaby</groupId>
    <artifactId>diffsert-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

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

To configure options in code, define a `DiffsertOptions` bean; it takes precedence over the properties. Defining
your own `Diffsert` bean replaces the auto-configured one, *including* the listener and metrics wiring; attach them
yourself with `diffsert.withListener(diffsertMetrics)` if needed.

### Spring Data

```java
Diffsert diffsert = new Diffsert(mongoTemplate, DiffsertOptions.builder()
        .ignoreForChangeDetection("jobRunId", "_updatedAt")
        .preserveExistingValue("_createdAt")
        .build());

diffsert.upsertAll(dtos, CustomerDto.class);          // collection from @Document
diffsert.upsertAll(dtos, "customers");               // or explicit
```

Entities are converted like `save()` does (`@Id`, `@Field`, custom converters); every entity needs an id. The type key
`_class` is removed by default (`withTypeKeyRemoved(false)` keeps it).

### Plain driver

```java
DiffsertWriter writer = new DiffsertWriter(DiffsertOptions.builder()
        .ignoreForChangeDetection("jobRunId", "_updatedAt")
        .build());

writer.upsertAll(database.getCollection("customers"), documents);
```

### Options

| Builder (`DiffsertOptions`)        | Property                   | Default   | Meaning                                                                                                 |
|------------------------------------|----------------------------|-----------|---------------------------------------------------------------------------------------------------------|
| `ignoreForChangeDetection(...)`    | `diffsert.ignore-fields`   | none      | Top-level fields that alone don't count as a change. Written along with real changes.                   |
| `preserveExistingValue(...)`       | `diffsert.preserve-fields` | none      | Top-level fields whose stored value is kept once set (e.g. `_createdAt`).                               |
| `replace()` / `merge()`            | `diffsert.mode`            | `replace` | `replace`: fields missing from the new document are removed. `merge`: fields only in the stored document are kept. |
| `ordered(...)`                     | `diffsert.ordered`         | `false`   | Ordered bulk writes stop at the first error; unordered ones continue and are faster.                    |
| `Diffsert.withTypeKeyRemoved(...)` | `diffsert.remove-type-key` | `true`    | Remove Spring Data's type key (`_class`) from converted entities.                                       |

### Results, listeners and metrics

`upsert`/`update` return a `WriteOutcome` (`INSERTED`, `UPDATED`, `UNCHANGED`, `NOT_FOUND`); the batch methods return
a `BatchWriteResult` with `requested`, `inserted`, `modified`, `unchanged()`, `notFound()` and the inserted ids.
`update`/`updateAll` never insert.

A `DiffsertListener` (`withListener(...)`, or any listener bean with the starter) is called after every write. With
Micrometer, the starter counts written documents as `diffsert.documents`, tagged with `collection` and `outcome`
(`inserted`, `updated`, `unchanged`, `not_found`).

For sessions, transactions or mixed bulk writes, `toWriteModels(...)` returns the write models without executing
them, and `buildPipeline(...)` the pipeline for a single document.

## Caveats

- **Small documents may still produce `replace` events.** MongoDB only logs a delta when it is smaller than the new
  document. For very small documents, or when almost every field changes, the oplog contains a full replacement and
  change streams emit a `replace` event. (In a test with ten short string fields, changing nine still produced an
  `update` event.) Consumers should handle `replace` events as well.
- **Unchanged documents are not touched.** They keep their old run metadata (e.g. `jobRunId`), so "not written in
  this run" cannot be used to detect deleted source records; track the ids seen in a run separately.
- **Keep field order and types stable.** A different field order counts as a change and rewrites the document, e.g.
  after reordering DTO fields. A numeric type change alone (`1` → `1L`) is written without ignored/preserved fields;
  with them, numerically equal values count as unchanged and the stored type is kept.
- **Arrays and nested documents are reported by path**, e.g. `tags.2` or `address.street`; shortened arrays appear in
  `truncatedArrays`.
- **Ignored and preserved fields are top-level only**; documents are matched by `_id` only.
- **Driver exceptions are not translated** to Spring's `DataAccessException`, so a `MongoBulkWriteException` can be
  inspected via `getWriteErrors()`. Documents written before or besides the failed ones are still reported to
  listeners and metrics.

## Building

```sh
mvn verify                                  # needs Docker (Testcontainers)
mvn verify -Dmongo.image=mongo:8.0          # against another server version
```

## License

[MIT](LICENSE)
