# Diffsert

Open source Java library (working name "Diffsert", groupId `dev.jbaby`, namespace already verified on Maven Central).
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

`Diffsert.buildPipeline` documents the exact pipeline in its Javadoc.

## Current state

- Single module, package `dev.jbaby.diffsert`: `Diffsert`, `DiffsertOptions` (immutable record + builder),
  `WriteOutcome`, `BatchWriteResult`.
- `DiffsertIT` (Testcontainers, `mongo:7.0.43` by default, single-node replica set) asserts the actual change stream
  events per scenario: insert, ignored-only no-op, changed fields only + preserved `_createdAt` + stable field order,
  removed fields, mixed batch, update never inserts, merge mode, no-options writer.
- `mvn verify` passes (all 8 ITs) against MongoDB 7.0.15 with Spring Boot BOM 4.0.8 (Spring Data 2025.1.7,
  driver 5.6.5, Testcontainers 2.0.5), Java 25. Plugin versions are pinned in `pluginManagement`.
  Testcontainers 2's `MongoDBContainer` needs `.withReplicaSet()` for change streams.
- The IT image is set by `-Dmongo.image=mongo:<tag>` (default `mongo:7.0.43`). All 8 ITs passed locally on
  5.0.33, 6.0.28, 7.0.43, 8.0.32, 8.2.12, 8.3.11 and 9.0.2 (2026-10-08).
- Tests encode the intended behavior: do not weaken assertions to make them pass without understanding why they fail.

## Next steps (in order)

1. ~~Build and run `mvn verify`~~ (done).
2. ~~CI~~ (done, not yet run on GitHub): `.github/workflows/ci.yml` runs `mvn verify` as a matrix over floating
   tags `mongo:5.0` … `mongo:9.0` (via `-Dmongo.image`), on push to `main`, PRs and weekly. The delta-oplog behavior
   is a server implementation detail, not a documented guarantee, so the supported versions must be tested; add
   new server lines to the matrix as they appear.
3. ~~Naming pass~~ (done): project name Diffsert is final; entry point `Diffsert`, options `DiffsertOptions`.
   In the module split, keep `Diffsert` as the Spring Data entry point and give the plain-driver core class its
   own name.
4. Split into modules: `diffsert-core` (plain driver, `Document` in/out, no Spring), `diffsert-spring-data`
   (MongoConverter-based entity conversion), `diffsert-spring-boot-starter` (auto-configuration, properties such as
   `diffsert.ignore-fields` / `diffsert.preserve-fields`, Micrometer counters for inserted/updated/unchanged).
5. README with the problem, the mechanism, supported versions, usage, and caveats (below).
6. Publishing (MIT license already in `LICENSE` and `pom.xml`): `central-publishing-maven-plugin`, sources/javadoc jars, GPG signing, release workflow.
7. Later ideas: reactive variant, nested (dotted) ignored/preserved fields, optional change stream verification
   helper (sentinel-based event collection as in the IT).

## Caveats to document for users

- Requires MongoDB 5.0+ (`$unsetField`, delta oplog entries) and a replica set for change streams.
- Unchanged documents keep their old run metadata (e.g. `jobRunId`), so "not touched in this run" cannot be
  used to detect deletions; track seen ids separately.
- Field order and BSON types must be stable between writes; otherwise the first write after a mapping change
  rewrites the document. `$eq` treats numeric types by value (1 == 1L).
- Changes inside arrays are reported as the whole array.
- Driver exceptions (e.g. `MongoBulkWriteException`) are thrown untranslated on purpose.

## Open decisions (ask the owner)

- Minimum Spring Data version (built against Spring Boot 4.0.x / Spring Data 2025.1). Java baseline is 25 (owner's
  choice; Spring Boot 4 itself only requires 17).
