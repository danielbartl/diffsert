# Diffsert

[![CI](https://github.com/danielbartl/diffsert/actions/workflows/ci.yml/badge.svg)](https://github.com/danielbartl/diffsert/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/dev.jbaby/diffsert-spring-boot-starter)](https://central.sonatype.com/artifact/dev.jbaby/diffsert-spring-boot-starter)

**Website:** https://danielbartl.github.io/diffsert/ · **User guide:** https://danielbartl.github.io/diffsert/guide.html

Change-aware upserts for MongoDB and Spring Data MongoDB. Only fields that really changed reach the oplog, so change
streams, Debezium and other CDC consumers see real changes instead of a full replace on every write. Unchanged
documents cause no write and no event at all.

> Status: early release (0.x); the API may still change before 1.0.

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

With ignored, preserved or version fields configured, the pipeline also compares the new document with the stored
one while leaving out the ignored fields, the version field and the preserved fields the stored document already has,
and keeps the stored document unchanged if nothing else differs. Preserved fields are copied back from the stored
document. The exact pipeline is documented in `DiffsertWriter.buildPipeline`.

## Requirements and supported versions

- Java 21 or later. CI tests on Java 21 and 25.
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

All modules are on Maven Central under `dev.jbaby`, current version `0.3.0`.

## Usage

### Spring Boot

```xml
<dependency>
    <groupId>dev.jbaby</groupId>
    <artifactId>diffsert-spring-boot-starter</artifactId>
    <version>0.3.0</version>
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

To configure options in code, define a `DiffsertOptions` bean; it takes precedence over the properties (with several,
only a `@Primary` one does). Defining your own `Diffsert` bean replaces the auto-configured one, *including* the
listener and metrics wiring; attach them yourself with `diffsert.withListener(diffsertMetrics)` if needed. The
auto-configured bean needs a single `MongoOperations` bean (or a `@Primary` one); with one template per database,
define a `Diffsert` per template yourself.

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
`_class` is removed by default (`withTypeKeyRemoved(false)` keeps it). Only the conversion is like `save()`: entity
callbacks, validation and mapping events don't run. Writes use the write concern of the client or database; one set
on the template doesn't apply, use `withWriteConcern(...)`.

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
| `versionField(...)`                | `diffsert.version-field`   | none      | Top-level field that counts real changes, see [Versions](#versions). Entities use their `@Version`.     |
| `Diffsert.withTypeKeyRemoved(...)` | `diffsert.remove-type-key` | `true`    | Remove Spring Data's type key (`_class`) from converted entities.                                       |
| `Diffsert.withAuditing(...)`       | `@EnableMongoAuditing`     | off       | Handle Spring Data's auditing annotations, see below.                                                   |

### Auditing

With `@EnableMongoAuditing` (or `diffsert.withAuditing(auditingHandler)` without the starter), Diffsert handles
Spring Data's auditing annotations on entities, so `@LastModifiedDate` only moves when something really changed:

```java
@Document("orders")
record Order(@Id String id, String status,
             @CreatedDate Instant createdAt, @LastModifiedDate Instant updatedAt) { }
```

| Annotation                             | Handled as      | Effect                                               |
|----------------------------------------|-----------------|------------------------------------------------------|
| `@CreatedDate`, `@CreatedBy`           | preserved field | set when the document is inserted, then kept         |
| `@LastModifiedDate`, `@LastModifiedBy` | ignored field   | written only together with a real change             |

- These fields are added to the configured options per entity type; `diffsert.optionsFor(Order.class)` shows the
  result. `Document`s are written as they are, without auditing.
- Every entity is marked like `save()` marks a new one: mutable entities get the values set in place, immutable ones
  need a wither or a single constructor, as with `save()`.
- A stored document without a created date, e.g. from before auditing was enabled, gets the current time on its next
  write, which writes it once.
- Only top-level properties are supported: entities with auditing annotations in embedded objects are rejected, and
  entity types with different auditing fields can't be mixed in one batch.

### Versions

A version field counts the real changes of a document. Entities get the field of their `@Version` property
automatically; for `Document`s, configure it with `versionField("version")`.

- It doesn't count as a change, and its incoming value is ignored, as is the version of the written entity (which is
  not updated either).
- A real change sets it to the stored value + 1, keeping the stored `int`/`long` type; a stored value that isn't a
  number (or is a date) fails the write. A no-op leaves it as it is.
- An insert, or a stored document without one, gets the initial value: 0, or 1 for primitive `@Version` types, as with
  `save()`. For `Document`s, `versionField("version", 1)` sets it.

So an application that also writes these documents with `save()` keeps working: a document inserted by Diffsert isn't
new for `save()`, and optimistic locking notices Diffsert's changes. Like `MongoTemplate.updateFirst`, Diffsert
increments the version without checking it, so its own writes always win. Keep the type key in that case
(`withTypeKeyRemoved(false)`), otherwise `_class` counts as a change whenever the other side wrote last.

### Results, listeners and metrics

`upsert`/`update` return a `WriteOutcome` (`INSERTED`, `UPDATED`, `UNCHANGED`, `NOT_FOUND`); the batch methods return
a `BatchWriteResult` with `requested`, `inserted`, `modified`, `unchanged()`, `notFound()` and the inserted ids.
`update`/`updateAll` never insert.

A `DiffsertListener` (`withListener(...)`, or any listener bean with the starter) is called after every write. With
Micrometer, the starter counts written documents as `diffsert.documents`, tagged with `collection` and `outcome`
(`inserted`, `updated`, `unchanged`, `not_found`).

`Diffsert` writes join a Spring-managed transaction (`MongoTransactionManager`, e.g. with `@Transactional`) like any
`MongoTemplate` operation. Listeners and metrics are called when a write runs, so they also count writes that are
rolled back later. For sessions without Spring, or mixed bulk writes, `toWriteModels(...)` returns the write models
without executing them, and `buildPipeline(...)` the pipeline for a single document.

## With Debezium

Debezium's MongoDB connector reports both `update` and `replace` change events as `op: "u"`. What else an event
carries depends on its `capture.mode`:

| `capture.mode`                         | `update` event (Diffsert)                              | `replace` event (`save()`, small documents)      |
|----------------------------------------|--------------------------------------------------------|--------------------------------------------------|
| `change_streams_update_full` (default) | `updateDescription`, plus the full document in `after` | full document in `after`, no `updateDescription` |
| `change_streams`                       | `updateDescription` only, no `after`                   | full document in `after`, no `updateDescription` |
| `*_with_pre_image` (MongoDB 6.0+)      | additionally the old document in `before`              | additionally `before`                            |

- **Unchanged documents produce no event in any mode**, because nothing is written.
- **Read changes from `updateDescription`, not from `after`.** In the default mode, `after` is the whole document, so
  it doesn't show what changed. Debezium also reads it from the collection when it processes the event, not at write
  time, so it can already contain later changes, or be `null` if the document was deleted in the meantime (unless
  `capture.mode.full.update.type=post_image`, MongoDB 6.0+).
- **An event without `updateDescription` is a replace** (see the caveat on small documents below): treat every field
  as possibly changed, or compare `before` and `after` in a pre-image mode.
- **Check `change_streams` pipelines before switching to Diffsert.** With `save()`/`replaceOne`, every event carried
  the full document, even in this mode. With Diffsert, update events have no `after`, and `ExtractNewDocumentState`
  emits only `_id` and the changed fields (nested ones as dotted keys such as `address.street`). A sink that expects
  whole documents would then write partial ones. Use `change_streams_update_full`, or build the sink for partial
  updates.
- **`ExtractNewDocumentState` keeps only `after`** in the default mode. If consumers need the changed fields, copy
  them into the flattened record with its `add.fields` option.

## Caveats

- **Small documents may still produce `replace` events.** MongoDB only logs a delta when it is smaller than the new
  document. For very small documents, or when almost every field changes, the oplog contains a full replacement and
  change streams emit a `replace` event. (In a test with ten short string fields, changing nine still produced an
  `update` event.) Consumers should handle `replace` events as well.
- **Unchanged documents are not touched.** They keep their old run metadata (e.g. `jobRunId`), so "not written in
  this run" cannot be used to detect deleted source records; track the ids seen in a run separately.
- **Keep field order and types stable.** A different field order counts as a change and rewrites the document, e.g.
  after reordering DTO fields. A numeric type change alone (`1` → `1L`) is written without ignored, preserved or
  version fields; with them, numerically equal values count as unchanged and the stored type is kept.
- **Arrays and nested documents are reported by path**, e.g. `tags.2` or `address.street`; shortened arrays appear in
  `truncatedArrays`.
- **Ignored and preserved fields are top-level only**; documents are matched by `_id` only.
- **Driver exceptions are not translated** to Spring's `DataAccessException`, so a `MongoBulkWriteException` can be
  inspected via `getWriteErrors()`. Documents written before or besides the failed ones are still reported to
  listeners and metrics.
- **The write concern must be acknowledged.** With `w: 0`, a write doesn't tell what it did, so Diffsert rejects such
  a collection before writing. A single write that fails on its write concern (e.g. a `w: majority` timeout) may have
  been applied but isn't reported to listeners and metrics: the driver doesn't say whether it changed anything.
- **Collations:** on a collection with a non-simple default collation, the comparison with ignored, preserved or
  version fields uses it, so a change it treats as equal (`Acme` → `ACME` when case-insensitive) isn't written.

## Building

```sh
./mvnw verify                               # needs Docker (Testcontainers)
./mvnw verify -Dmongo.image=mongo:8.0       # against another server version
```

## License

[MIT](LICENSE)
