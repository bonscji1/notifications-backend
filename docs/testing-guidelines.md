# Testing Guidelines

Rules and conventions for testing in `notifications-backend`, derived from existing patterns. All agents MUST follow these during implementation and review.

## Framework and Annotations

All tests in this repository are Quarkus-based integration tests using JUnit 5. There are no separate unit test source sets.

- Annotate every test class with `@QuarkusTest`.
- Register the module's `TestLifecycleManager` with `@QuarkusTestResource(TestLifecycleManager.class)`.
- If the test needs database isolation, extend `DbIsolatedTest` (backend module only). This runs `DbCleaner.clean()` before and after each test method, deleting all rows and restoring default bundle/app/event-type records.
- Use `@TestProfile(ProdTestProfile.class)` only when a test must run under the `prod` Quarkus configuration profile.
- The codebase uses `@QuarkusTest` exclusively; `@QuarkusIntegrationTest` is not used.

## TestLifecycleManager Pattern

Each module has its own `TestLifecycleManager` implementing `QuarkusTestResourceLifecycleManager`. These are module-specific implementations that must be imported from the same module as the test.

| Module | What it starts | Notes |
|---|---|---|
| `backend` | PostgreSQL (Testcontainers) + WireMock | Installs `pgcrypto` extension |
| `engine` | PostgreSQL (Testcontainers) + WireMock + InMemoryConnector | Installs `pgcrypto` extension |
| `aggregator` | PostgreSQL (Testcontainers) + InMemoryConnector | No WireMock |
| `connector-common` / `connector-common-v2` | WireMock only | No database |
| Connector modules (email, drawer, etc.) | Delegates to parent connector-common lifecycle | Inherits from connector-common |

PostgreSQL version is controlled by `TestConstants.POSTGRES_MAJOR_VERSION` (currently `"16"`).

## Mocking

### Quarkus CDI Mocks

- Use `@InjectMock` (from `io.quarkus.test`) to replace a CDI bean entirely with a Mockito mock. Commonly used for configuration beans like `BackendConfig`.
- Use `@InjectSpy` (from `io.quarkus.test.junit.mockito`) to wrap a real bean with a Mockito spy. Used when you need to verify calls but keep real behavior.
- Use `@RestClient @InjectMock` together to mock MicroProfile REST clients (e.g., `SourcesPskService`).
- Configure mock behavior with `when(mock.method()).thenReturn(value)` in `@BeforeEach` or directly in test methods.

### WireMock for External Services

WireMock is managed via `MockServerLifecycleManager` (in `common` module's test sources). Access the server through:
- `MockServerLifecycleManager.getClient()` -- returns the `WireMockServer` instance
- `MockServerLifecycleManager.getMockServerUrl()` -- HTTP URL
- `MockServerLifecycleManager.getMockServerHttpsUrl()` -- HTTPS URL (with test keystore)

The `MockServerConfig` helper (backend module) provides pre-built RBAC stubs:
```java
MockServerConfig.addMockRbacAccess(xRhIdentity, RbacAccess.FULL_ACCESS);
```
Available access levels: `FULL_ACCESS`, `NOTIFICATIONS_READ_ACCESS_ONLY`, `NOTIFICATIONS_ACCESS_ONLY`, `READ_ACCESS`, `NO_ACCESS`. These load JSON fixtures from `src/test/resources/rbac-examples/`.

Always call `getClient().resetAll()` in `@BeforeEach` when tests add WireMock stubs to avoid test pollution.

## Reactive Messaging (Kafka) Testing

Kafka channels are replaced with `InMemoryConnector` from SmallRye in test `application.properties`:
```properties
mp.messaging.incoming.ingress.connector=smallrye-in-memory
mp.messaging.outgoing.egress.connector=smallrye-in-memory
```

In connector integration tests, inject and use:
```java
@Inject @Any
InMemoryConnector inMemoryConnector;

InMemorySource<Message<JsonObject>> source = inMemoryConnector.source("incomingmessages");
InMemorySink<String> sink = inMemoryConnector.sink("outgoingmessages");
```

Call `outgoingMessageSink.clear()` in `@BeforeEach` and `InMemoryConnector.clear()` in lifecycle stop.

## Test Data Setup

### ResourceHelpers

Each module has its own `ResourceHelpers` class for creating test entities. The backend module's `ResourceHelpers` is an `@ApplicationScoped @Transactional` bean providing factory methods for test entities:
- `createBundle()`, `createApplication()`, `createEventType()` -- core domain entities
- `createWebhookEndpoint()`, `createEndpoint()` -- integration endpoints
- `createBehaviorGroup()`, `createDefaultBehaviorGroup()` -- behavior groups
- `createTemplate()`, `createInstantEmailTemplate()`, `createAggregationEmailTemplate()` -- templates (see `docs/template-guidelines.md` for template testing patterns)

The `common`, `engine`, and `aggregator` modules have their own `ResourceHelpers` variants with module-specific methods.

Inject it via `@Inject ResourceHelpers resourceHelpers;`.

### TestConstants

Use constants from `TestConstants` for consistent test identifiers:
- `DEFAULT_ACCOUNT_ID`, `DEFAULT_ORG_ID`, `DEFAULT_USER`
- `API_INTEGRATIONS_V_1_0`, `API_NOTIFICATIONS_V_1_0`, etc. (API path constants)
- `POSTGRES_MAJOR_VERSION`

### TestHelpers (backend module)

Provides identity header encoding for REST-assured tests:
- `encodeRHIdentityInfo(accountId, orgId, username)` -- user identity
- `encodeRHServiceAccountIdentityInfo(orgId, username, uuid)` -- service account
- `createTurnpikeIdentityHeader(username, roles...)` -- internal/admin identity

### CrudTestHelpers (backend module)

Abstract class with static helper methods for REST-assured CRUD operations against internal APIs. Used for creating bundles, applications, event types, and templates via HTTP in integration tests.

## Connector Integration Tests

Connector modules extend `BaseConnectorIntegrationTest` (or `BaseHttpConnectorIntegrationTest` for HTTP connectors):

1. Override `buildIncomingPayload(String targetUrl)` to create the connector-specific message.
2. Override `getConnectorSpecificTargetUrl()` for the WireMock stub URL.
3. Optionally override `getRemoteServerPath()` for WireMock URL path matching.
4. The base class handles InMemory channel setup, metrics saving, and message injection.

## REST API Testing

Use REST-assured (`io.restassured.RestAssured.given()`) for HTTP endpoint tests:
```java
given()
    .header(identityHeader)
    .contentType(JSON)
    .body(payload)
    .when().post(API_PATH)
    .then().statusCode(200);
```

Always set the `x-rh-identity` header using `TestHelpers.encodeRHIdentityInfo()` and configure RBAC mock access via `MockServerConfig.addMockRbacAccess()`.

## Metrics Testing

Use `MicrometerAssertionHelper` (in `common` test sources) for counter/timer assertions:
1. Call `saveCounterValuesBeforeTest(counterNames...)` in `@BeforeEach`.
2. After the operation, assert increments with `awaitAndAssertCounterIncrement(counterName, expectedIncrement)`.

This avoids issues with `MeterRegistry.clear()` not working properly with `@ApplicationScoped` beans.

## Async Assertions

Use Awaitility for asynchronous assertions (already a dependency):
```java
import static org.awaitility.Awaitility.await;
await().atMost(Duration.ofSeconds(10)).until(() -> condition);
```

## Database Isolation

For backend module tests that write to the database:
1. Extend `DbIsolatedTest` -- this is the standard pattern.
2. `DbCleaner` deletes all rows from entity tables in dependency order, then re-creates default RHEL/Policies bundle/app/event-type.
3. Do NOT manually truncate tables or manage transactions in test setup/teardown when extending `DbIsolatedTest`.

For modules without `DbIsolatedTest` (engine, aggregator), the `TestLifecycleManager` starts a fresh PostgreSQL container per test run, providing natural isolation.

## Test application.properties

Each module has `src/test/resources/application.properties` to override production config:
- Replace Kafka connectors with `smallrye-in-memory`
- Disable caches (`quarkus.cache.enabled=false`)
- Point REST clients to WireMock URLs (done programmatically by `TestLifecycleManager`)

Do not duplicate settings already handled by `TestLifecycleManager` (datasource URL, credentials).

## JaCoCo Coverage

Coverage is configured globally in the root `pom.xml`:
- Uses `quarkus-jacoco` (test dependency) + `jacoco-maven-plugin`
- Excludes `QuarkusClassLoader` from instrumentation
- Reports output to `target/jacoco-report/`
- SonarQube reads from `target/jacoco-report/jacoco.xml`
- No minimum coverage thresholds are enforced

## Naming Conventions

- Test classes: `<ClassUnderTest>Test.java` (e.g., `BehaviorGroupRepositoryTest`)
- Integration tests with broader scope: `<Feature>ITest.java` (e.g., `LifecycleITest`)
- Connector tests: `<Connector>ConnectorIntegrationTest.java`
- Test helpers: `<Domain>TestHelpers.java` (e.g., `ErrataTestHelpers`, `PatchTestHelpers`)
- Test methods: descriptive camelCase (`shouldThrowExceptionWhenCreatingWithExistingDisplayName`)
- Mock resources: `<Service>MockResource.java` (e.g., `OidcServerMockResource`)

## Checkstyle

Checkstyle runs on test sources too (`includeTestSourceDirectory=true`). Test code must follow the same style rules as production code (no tabs, standard whitespace rules).

## Key Dependencies (from root pom.xml)

| Dependency | Version Property | Purpose |
|---|---|---|
| Testcontainers | `testcontainers.version` (2.0.4) | PostgreSQL containers |
| WireMock | `wiremock.version` (3.13.2) | HTTP service mocking |
| Quarkus JaCoCo | BOM-managed | Coverage instrumentation |
| SmallRye InMemoryConnector | BOM-managed | Kafka channel replacement |
| Awaitility | BOM-managed | Async test assertions |
| REST-assured | BOM-managed | HTTP API testing |
| Mockito | BOM-managed (via Quarkus) | `@InjectMock`, `@InjectSpy` |
