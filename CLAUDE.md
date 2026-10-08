# Diffsert

Open source Java library (name Diffsert, final; groupId `dev.jbaby`, namespace already verified on Maven Central).
Change-aware upserts for Spring Data MongoDB: when documents are written, only the fields that really changed
reach the oplog and therefore change streams / Debezium. Unchanged documents produce no write and no event.

## Origin and the problem it solves

Extracted from a real-world sync job that copies another database into MongoDB in batches of 1000 mapped DTOs.
Debezium listens to the MongoDB change stream and downstream logic reacts only when specific properties change. Plain `replaceOne`/`save()` produces a `replace` event with the whole document on every run,
so downstream logic fired for every document even when nothing relevant changed.

## How it works (core mechanism, keep this intact)

- Every write is a **pipeline update** (`updateOne(filter, List<Bson>, upsert)`) with `$replaceWith`, never a replace.
  MongoDB 5.0+ diffs the result against the stored document and writes only the delta to the oplog. Change events
  are then `update` events whose `updateDescription.updatedFields` / `removedFields` contain only real changes.
  Identical result = no-op (matched but not modified, no event).
- The new document is wrapped in `$literal` so values starting with `$` are never interpreted as expressions.
- `ignoredFields` (e.g. `jobRunId`, `_updatedAt`): compared via `$unsetField` on both sides; if only these differ,
  `$$ROOT` is kept (no-op). Otherwise they are written along with the real changes.
- `preservedFields` (e.g. `_createdAt`): merged back from the stored doc via `$mergeObjects` + `$type != "missing"`.
- On an upserting insert, `$$ROOT` is `{_id: ...}`; the pipeline checks `$$ROOT != {_id: "$_id"}` so a new document is
  never reduced to just its `_id`.
- Modes: `REPLACE` (missing fields are removed) and `MERGE` (`$mergeObjects: [$$ROOT, doc]`, foreign fields kept).
- Entities are converted with Spring Data's `MongoConverter` (so `@Id`, `@Field`, custom converters apply);
  the type key (`_class`) is removed by default.

`DiffsertWriter.buildPipeline` (diffsert-core) documents the exact pipeline in its Javadoc.

## Current state

- Multi-module build, parent `diffsert-parent` (Spring Boot BOM 4.0.8 imported, plugin versions pinned, Java 25):
  - `diffsert-core`, package `dev.jbaby.diffsert`, only `mongodb-driver-sync`: `DiffsertWriter` (pipeline + writes,
    takes a `MongoCollection<Document>` per call), `DiffsertOptions` (immutable record + builder: mode, ignored,
    preserved, ordered), `WriteOutcome`, `BatchWriteResult`, `DiffsertListener` (called after every write).
  - `diffsert-spring-data`, package `dev.jbaby.diffsert.spring`: `Diffsert` (entity conversion via `MongoConverter`,
    `withTypeKeyRemoved`, delegates to `DiffsertWriter`).
  - `diffsert-spring-boot-starter`, package `dev.jbaby.diffsert.boot`: `DiffsertAutoConfiguration` (after Boot 4's
    `DataMongoAutoConfiguration`; `@ConditionalOnMissingBean`; a `DiffsertOptions` bean wins over properties; all
    `DiffsertListener` beans are notified, each isolated: failures are logged, never thrown to the caller),
    `DiffsertProperties` (`diffsert.mode/ignore-fields/preserve-fields/ordered/remove-type-key`), `DiffsertMetrics`
    (counter `diffsert.documents{collection,outcome}`, only with a `MeterRegistry`).
- Tests: `DiffsertWriterIT` (core, Documents) and `DiffsertIT` (spring-data, entities) assert the actual change stream
  events per scenario: insert, ignored-only no-op, changed fields only + preserved `_createdAt` + stable field order,
  removed fields, mixed batch, update never inserts, merge mode, no-options writer, plus listener reporting.
  Starter: `DiffsertAutoConfigurationTest` (context runner, no server) and `DiffsertAutoConfigurationIT`.
  `TestMongo` (core test-jar) holds the container setup and the sentinel-based `eventsDuring` helper.
- The IT image is set by `-Dmongo.image=mongo:<tag>` (default `mongo:7.0.43`). Testcontainers 2's `MongoDBContainer`
  needs `.withReplicaSet()` for change streams. The ITs passed on 5.0.33, 6.0.28, 7.0.43, 8.0.32, 8.2.12, 8.3.11
  and 9.0.2 (2026-10-08).
- Landing page: `site/index.html` (single file, no build), deployed to https://danielbartl.github.io/diffsert/ by
  `.github/workflows/pages.yml` on changes under `site/`. Keep it in sync with the README.
- Tests encode the intended behavior: do not weaken assertions to make them pass without understanding why they fail.

## Next steps (in order)

1. ~~Build and run `mvn verify`~~ (done).
2. ~~CI~~ (done): `.github/workflows/ci.yml` runs `mvn verify` as a matrix over floating
   tags `mongo:5.0` … `mongo:9.0` (via `-Dmongo.image`), on push to `main`, PRs and weekly. The delta-oplog behavior
   is a server implementation detail, not a documented guarantee, so the supported versions must be tested; add
   new server lines to the matrix as they appear.
3. ~~Naming pass~~ (done): project name Diffsert is final; entry point `Diffsert`, options `DiffsertOptions`.
   In the module split, keep `Diffsert` as the Spring Data entry point and give the plain-driver core class its
   own name.
4. ~~Split into modules~~ (done, see Current state).
5. ~~README~~ (done).
6. Publishing (MIT license already in `LICENSE` and `pom.xml`): first move `TestMongo` from the core test-jar into
   an unpublished `diffsert-test-support` module (a test-jar breaks `-Dmaven.test.skip` and would be deployed); `central-publishing-maven-plugin`, sources/javadoc jars, GPG signing, release workflow.
7. Later ideas: reactive variant, nested (dotted) ignored/preserved fields, optional change stream verification
   helper (sentinel-based event collection as in the IT).

## Caveats (documented in README; verified on 5.0/7.0/9.0, pinned by DiffsertWriterIT)

- Requires MongoDB 5.0+ (`$unsetField`, delta oplog entries) and a replica set for change streams.
- Small documents / near-total rewrites still produce `replace` events: the server logs a delta only when it is
  smaller than the post-image (10 short string fields: 9 changed = `update`, 10 = `replace`).
- Unchanged documents keep their old run metadata (e.g. `jobRunId`), so "not touched in this run" cannot be
  used to detect deletions; track seen ids separately.
- Field order and BSON types must be stable: reordering counts as a change. A numeric type change alone (1 -> 1L)
  is written by the plain pipeline, but with ignored/preserved fields `$eq` treats it as equal and keeps the
  stored type.
- Arrays and nested documents are reported by path (`tags.2`, `address.street`); shortened arrays via
  `truncatedArrays`. (An earlier note claiming "whole array" was wrong.)
- Driver exceptions (e.g. `MongoBulkWriteException`) are thrown untranslated on purpose.

## Open decisions (ask the owner)

- Minimum Spring Data version (built against Spring Boot 4.0.x / Spring Data 2025.1). Java baseline is 25 (owner's
  choice; Spring Boot 4 itself only requires 17).
