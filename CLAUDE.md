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
- `_id` is moved to the front of the new document: MongoDB always stores it first, so anywhere else every write would
  be a modification whose event lists `_id` (probed on 7.0; pinned by `writesIdFirstWhereverTheDocumentHasIt`).
- `ignoredFields` (e.g. `jobRunId`, `_updatedAt`): compared via `$unsetField` on both sides; if only these differ,
  `$$ROOT` is kept (no-op). Otherwise they are written along with the real changes.
- `preservedFields` (e.g. `_createdAt`): merged back from the stored doc via `$mergeObjects` + `$type != "missing"`.
- `versionField` (e.g. `version`): left out of the comparison like ignored fields, incoming value ignored; on a real
  change set to stored + 1 (`$add` keeps int32/int64), or to `initialVersion` (Integer or Long) on insert / when the
  stored doc has none (missing or null). Like `MongoTemplate.updateFirst`, no version check of its own.
- On an upserting insert, `$$ROOT` is `{_id: ...}`; the pipeline checks `$$ROOT != {_id: "$_id"}` so a new document is
  never reduced to just its `_id`.
- Modes: `REPLACE` (missing fields are removed) and `MERGE` (`$mergeObjects: [$$ROOT, doc]`, foreign fields kept).
- Entities are converted with Spring Data's `MongoConverter` (so `@Id`, `@Field`, custom converters apply);
  the type key (`_class`) is removed by default.

`DiffsertWriter.buildPipeline` (diffsert-core) documents the exact pipeline in its Javadoc.

## Current state

- Multi-module build, parent `diffsert-parent` (Spring Boot BOM 4.0.8 imported, plugin versions pinned, Java 21 minimum):
  - `diffsert-core`, package `dev.jbaby.diffsert`, only `mongodb-driver-sync`: `DiffsertWriter` (pipeline + writes,
    takes a `MongoCollection<Document>` per call), `DiffsertOptions` (immutable record + builder: mode, ignored,
    preserved, ordered, versionField + initialVersion; the old 4-arg constructor is kept), `WriteOutcome`, `BatchWriteResult`, `DiffsertListener` (called after every write).
  - `diffsert-spring-data`, package `dev.jbaby.diffsert.spring`: `Diffsert` (entity conversion via `MongoConverter`,
    `withTypeKeyRemoved`, delegates to `DiffsertWriter`). `withAuditing(AuditingHandler)`: `EntityAuditing` derives
    preserved (`@CreatedDate/@CreatedBy`) and ignored (`@LastModifiedDate/@LastModifiedBy`) fields per entity type
    (top-level only, nested ones rejected), added to the configured options (`optionsFor(type)`); entities are marked
    with `markCreated` (+ `markModified` if `modifyOnCreate = false` left them empty). `Document`s never get derived
    fields (the owner's own jobs write Jackson-built Documents). Always (no opt-in): an entity's `@Version` property
    becomes the version field, initial value as `save()` initializes it (1 for primitives, else 0; Integer for int
    types, else Long); a different configured version field is rejected. A batch must agree on the derived fields.
  - `diffsert-spring-boot-starter`, package `dev.jbaby.diffsert.boot`: `DiffsertAutoConfiguration` (after Boot 4's
    `DataMongoAutoConfiguration`; `@ConditionalOnMissingBean`; a `DiffsertOptions` bean wins over properties; all
    `DiffsertListener` beans are notified, each isolated: failures are logged, never thrown to the caller; uses the
    `mongoAuditingHandler` bean from `@EnableMongoAuditing`, by name, because JPA registers another handler),
    `DiffsertProperties` (`diffsert.mode/ignore-fields/preserve-fields/ordered/version-field/remove-type-key`), `DiffsertMetrics`
    (counter `diffsert.documents{collection,outcome}`, only with a `MeterRegistry`).
- Tests: `DiffsertWriterIT` (core, Documents) and `DiffsertIT` (spring-data, entities) assert the actual change stream
  events per scenario: insert, ignored-only no-op, changed fields only + preserved `_createdAt` + stable field order,
  removed fields, mixed batch, update never inserts, merge mode, no-options writer, plus listener reporting.
  `DiffsertIT` also covers Spring-managed transactions (`MongoTransactionManager` sharing the template's factory):
  writes join the transaction (events carry a `txnNumber`, delta still applies), rollback leaves no events, and
  listeners are called at write time, so they also see rolled-back writes. Auditing: insert sets created/modified,
  audit-only differences are a no-op, real changes move only the modified fields, `modifyOnCreate = false`,
  Documents untouched, nested fields and mixed batches rejected; the starter IT runs it with Boot's own Mongo
  auto-configuration and `@EnableMongoAuditing`. Versions: core ITs pin initial value, no-op/real change, incoming
  value ignored but its position kept, stored type kept, stored doc without version, failure on a non-numeric stored
  value; `DiffsertIT` mixes Diffsert with `save()` (a Diffsert change makes a stale `save()` fail with
  `OptimisticLockingFailureException`; primitive versions start at 1, otherwise `save()` would insert).
  Starter: `DiffsertAutoConfigurationTest` (context runner, no server) and `DiffsertAutoConfigurationIT`.
  `TestMongo` (module `diffsert-test-support`, package `dev.jbaby.diffsert.testsupport`, never published) holds
  the container setup and the sentinel-based `eventsDuring` helper.
- The IT image is set by `-Dmongo.image=mongo:<tag>` (default `mongo:7.0.43`). Testcontainers 2's `MongoDBContainer`
  needs `.withReplicaSet()` for change streams. The ITs passed on 5.0.33, 6.0.28, 7.0.43, 8.0.32, 8.2.12, 8.3.11
  and 9.0.2 (2026-10-08).
- Website (no build), deployed to https://danielbartl.github.io/diffsert/ by `.github/workflows/pages.yml` on changes
  under `site/`: `site/index.html` (landing page), `site/guide.html` (user guide: every option, Spring integration,
  events, errors, recipes, troubleshooting; 0.2.0 features marked), shared `site/site.css` (color tokens, header, code,
  tables, footer) and `site/site.js` (theme toggle, copy buttons). Keep README, landing page and guide in sync with
  the code. Visual check: headless `chromium --screenshot` (dark theme via `localStorage` "diffsert-theme").
- Tests encode the intended behavior: do not weaken assertions to make them pass without understanding why they fail.

## Next steps (in order)

1. ~~Build and run `mvn verify`~~ (done).
2. ~~CI~~ (done): `.github/workflows/ci.yml` runs `mvn verify` as a matrix over Java 21 and 25 and floating
   tags `mongo:5.0` … `mongo:9.0` (via `-Dmongo.image`), on push to `main`, PRs and weekly. The delta-oplog behavior
   is a server implementation detail, not a documented guarantee, so the supported versions must be tested; add
   new server lines to the matrix as they appear. MongoDB 8.0 and 8.3 run on `ubuntu-22.04`: they refuse to start on
   ubuntu-latest's kernel `7.0.0-*-azure` (SERVER-136848, the 6.19+ startup check reads `uname -r`, not the
   upstream patch level). Move them back once a server patch fixes the check.
3. ~~Naming pass~~ (done): project name Diffsert is final; entry point `Diffsert`, options `DiffsertOptions`.
   In the module split, keep `Diffsert` as the Spring Data entry point and give the plain-driver core class its
   own name.
4. ~~Split into modules~~ (done, see Current state).
5. ~~README~~ (done).
6. ~~Publishing setup~~ (done; 0.1.1 published with Java 21 support, see Releasing below).
7. Later ideas: reactive variant, nested (dotted) ignored/preserved fields, optional change stream verification
   helper (sentinel-based event collection as in the IT).

## Releasing

- Push a tag `vX.Y.Z` on `main`. `.github/workflows/release.yml` sets the version from the tag (`versions:set`,
  the repo stays on `-SNAPSHOT`), runs `mvn -Prelease deploy` (all tests, sources/javadoc jars, BouncyCastle GPG
  signing, `central-publishing-maven-plugin` with `autoPublish=true`, `waitUntil=published`), then creates a GitHub
  release. Published versions are permanent.
- Build with Java 21+ and the Maven Wrapper (`./mvnw`, Maven 3.9.16 with pinned SHA-256); release builds use Java 21.
  CI tests Java 21 and 25. Maven 3.10.0
  breaks `central-publishing-maven-plugin` 0.11.0: it stages `maven-metadata-local.xml` and `_remote.repositories`
  into the bundle, and Central rejects it ("Bundle has content that does NOT have a .pom file"). Only move to 3.10+
  once a plugin release handles it, and check the bundle (dry run below) first.
- Parent POM carries the Central metadata (url, MIT license, developer without email, scm); `diffsert-parent` is
  published too. `diffsert-test-support` is excluded via `excludeArtifacts`.
- Repo secrets: `CENTRAL_USERNAME` / `CENTRAL_PASSWORD` (Central Portal user token for namespace `dev.jbaby`),
  `MAVEN_GPG_KEY` (armored secret key) / `MAVEN_GPG_PASSPHRASE`. Signing key: ed25519 `1EF738AE00E644CF`
  (Daniel Bartl <dev@danielbartl.com>), public key on keys.openpgp.org.
- Dry run locally: `versions:set` in a scratch copy, then `./mvnw -Prelease deploy -DskipTests
  -DcentralBaseUrl=http://127.0.0.1:9` with a throwaway key (MAVEN_GPG_KEY) and a settings.xml with a dummy
  `central` server. The upload fails, but `target/central-publishing/central-bundle.zip` is built: it must contain
  only `dev/jbaby/<artifact>/<version>/` entries. (`-DskipPublishing=true` skips the bundle entirely.)
- After a release: bump the install snippets in README, `site/index.html` and `site/guide.html` to the new version,
  and check the guide's "Written for" note and its version marks.

## Caveats (documented in README; verified on 5.0/7.0/9.0, pinned by DiffsertWriterIT)

- Requires MongoDB 5.0+ (`$unsetField`, delta oplog entries) and a replica set for change streams.
- Small documents / near-total rewrites still produce `replace` events: the server logs a delta only when it is
  smaller than the post-image (10 short string fields: 9 changed = `update`, 10 = `replace`).
- Unchanged documents keep their old run metadata (e.g. `jobRunId`), so "not touched in this run" cannot be
  used to detect deletions; track seen ids separately.
- Field order and BSON types must be stable: reordering counts as a change. A numeric type change alone (1 -> 1L)
  is written by the plain pipeline, but with ignored/preserved/version fields `$eq` treats it as equal and keeps the
  stored type.
- Arrays and nested documents are reported by path (`tags.2`, `address.street`); shortened arrays via
  `truncatedArrays`. (An earlier note claiming "whole array" was wrong.)
- Driver exceptions (e.g. `MongoBulkWriteException`) are thrown untranslated on purpose.
- Debezium (README "With Debezium"; from the 2.7 docs and connector source, not covered by our tests): `update` and
  `replace` both become `op: "u"`, a replace has no `updateDescription`. Default `capture.mode`
  `change_streams_update_full` adds the looked-up full document as `after`. With `change_streams`, Diffsert's
  update events have no `after`, so `ExtractNewDocumentState` emits partial documents where `save()` gave full ones.

## Open decisions (ask the owner)

- Minimum Spring Data version (built against Spring Boot 4.0.x / Spring Data 2025.1). Owner (2026-10-21): Boot 4+
  only for now; a Spring Data 4.5 / Boot 3.5 compatibility check can come later.
- ~~`@Version` on entities~~: decided (2026-10-21) to increment the version in the pipeline on real changes
  (`versionField`), not to reject such entities.
