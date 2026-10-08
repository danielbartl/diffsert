# Diffsert

Change-aware upserts for Spring Data MongoDB. Only fields that really changed reach the oplog, so change streams,
Debezium and other CDC consumers see real changes instead of a full replace on every write.

> Status: early work in progress, not yet released.

## Usage

```java
Diffsert diffsert = new Diffsert(mongoTemplate, DiffsertOptions.builder()
        .ignoreForChangeDetection("jobRunId", "_updatedAt")   // alone, these are not a change
        .preserveExistingValue("_createdAt")                    // stored value wins once set
        .build());

BatchWriteResult result = diffsert.upsertAll(dtos, CustomerDto.class);
// requested=1000, inserted=3, updated=12, unchanged=985, notFound=0

WriteOutcome outcome = diffsert.upsert(dto);   // INSERTED / UPDATED / UNCHANGED / NOT_FOUND
```

### As Spring beans

```java
@Configuration(proxyBeanMethods = false)
class DiffsertConfig {

    @Bean
    Diffsert replicationDiffsert(MongoTemplate mongoTemplate) {
        return new Diffsert(mongoTemplate, DiffsertOptions.builder()
                .ignoreForChangeDetection("jobRunId", "_updatedAt")
                .preserveExistingValue("_createdAt")
                .build());
    }
}
```

## Requirements

MongoDB 5.0 or later. Change streams require a replica set.

## License

[MIT](LICENSE)
