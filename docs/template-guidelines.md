# Template Guidelines

Rules and conventions for notification templates in `notifications-backend`, derived from existing patterns in the `common-template` module. All agents MUST follow these during implementation and review.

## 1. Architecture Overview

The `common-template` module uses **Quarkus Qute** (`quarkus-qute` dependency) for rendering notification templates. `TemplateService` is an `@ApplicationScoped` CDI bean marked with `@Startup` that loads all template mappings at application start and validates every declared template file exists on the classpath.

Auto-escaping is **disabled** for `.json` and `.html` content types via `application.properties`:
```properties
quarkus.qute.content-types.json=none
quarkus.qute.content-types.html=none
```

## 2. Directory Structure

Template files live under `common-template/src/main/resources/templates/` and are organized by integration root folder, then by application subfolder:

```
templates/
  drawer/          # DRAWER notifications
  email/           # EMAIL_BODY, EMAIL_TITLE, EMAIL_DAILY_DIGEST_*
  slack/           # SLACK
  ms_teams/        # MS_TEAMS
  google_chat/     # GOOGLE_CHAT
```

Within each root folder, subfolders use **PascalCase** application names (e.g. `Advisor/`, `CostManagement/`, `Policies/`, `OCM/`). Shared includes go in a `Common/` subfolder. A `Default/` subfolder holds system-level fallback templates. A `Secure/` subfolder under `email/` holds secured-environment variants.

### File naming conventions

| Integration type | Extension | Example file name |
|---|---|---|
| DRAWER | `.md` | `newRecommendationBody.md` |
| EMAIL_BODY | `.html` | `newRecommendationInstantEmailBody.html` |
| EMAIL_TITLE | `.txt` | `instantEmailTitle.txt` |
| EMAIL_DAILY_DIGEST_BODY | `.html` | `dailyEmailBody.html` |
| SLACK / MS_TEAMS / GOOGLE_CHAT | `.json` | `default.json`, `newSubscriptionBugfixErrata.json` |

File names use **camelCase** and encode the event type and purpose (e.g. `deactivatedRecommendationBody.md`, `clusterUpdateInstantEmailBody.html`).

## 3. TemplateDefinition and IntegrationType

`TemplateDefinition` is a Java record with five fields:

```java
record TemplateDefinition(IntegrationType integrationType, String bundle,
    String application, String eventType, boolean isBetaVersion)
```

A convenience constructor omits `isBetaVersion` (defaults to `false`).

`IntegrationType` is an enum that maps each type to a root folder:
- `DRAWER` -> `"drawer"`
- `EMAIL_BODY`, `EMAIL_TITLE` -> `"email"`
- `EMAIL_DAILY_DIGEST_BODY`, `EMAIL_DAILY_DIGEST_BUNDLE_AGGREGATION_BODY`, `EMAIL_DAILY_DIGEST_BUNDLE_AGGREGATION_TITLE` -> `"email"`
- `SLACK` -> `"slack"`, `MS_TEAMS` -> `"ms_teams"`, `GOOGLE_CHAT` -> `"google_chat"`

Rules:
- NEVER add a new root folder string to `IntegrationType` without also creating the corresponding directory under `templates/`.
- Daily digest templates use `EMAIL_DAILY_DIGEST_BODY` with `eventType=null`; bundle-level aggregation uses `EMAIL_DAILY_DIGEST_BUNDLE_AGGREGATION_BODY`.

## 4. Template Mapping Classes

Template registrations live in `com.redhat.cloud.notifications.qute.templates.mapping`. Each class declares a `public static final Map<TemplateDefinition, String> templatesMap` built with `Map.ofEntries(...)`.

Existing mapping classes, loaded in `TemplateService.init()`:
- `DefaultTemplates` -- system-level fallback entries (null bundle/app/eventType)
- `AnsibleAutomationPlatform`, `Console`, `OpenShift`, `Rhel`, `SubscriptionServices` -- per-bundle entries
- `SecureEmailTemplates` -- loaded instead of the above when `notifications.use-secured-email-templates.enabled=true`
- `DefaultInstantEmailTemplates` -- loaded only when `notifications.use-default-template=true` (stage-only)

Rules for mapping classes:
- Define `BUNDLE_NAME`, app name constants (e.g. `ADVISOR_APP_NAME`), folder name constants (e.g. `ADVISOR_FOLDER_NAME = "Advisor/"`), and event type constants as `static final String` fields.
- The map value is a **relative path** from the integration root folder to the template file, e.g. `"Advisor/newRecommendationBody.md"`. The root folder prefix is added by `TemplateService.buildTemplateFilePath()`.
- Every entry in the map MUST have a corresponding file on disk. `checkTemplatesConsistency()` will throw `TemplateNotFoundException` at startup if any file is missing.

## 5. Three-Tier Fallback Resolution

`TemplateService.compileTemplate()` resolves templates using a three-tier fallback:

1. **Exact match**: `(integrationType, bundle, application, eventType)`
2. **Application default**: `(integrationType, bundle, application, null)` -- eventType dropped
3. **System default**: `(integrationType, null, null, null)` -- bundle and application also dropped

If all three fail and the definition is a **beta version** (`isBetaVersion=true`), the entire resolution restarts with `isBetaVersion=false` (GA fallback). If still not found, `TemplateNotFoundException` is thrown.

Rules:
- Always register a system-level default in `DefaultTemplates` for each `IntegrationType` that needs a catch-all.
- When adding a new event type, you may rely on fallback instead of creating a dedicated template, but review whether the default output is acceptable.
- Beta templates are optional. Do not create beta-specific files unless the content must differ from GA.

## 6. Rendering Entry Points

| Method | Data binding | Use case |
|---|---|---|
| `renderTemplate(TemplateDefinition, Map)` | Map accessible via `{data.*}` | Drawer, Slack, MS Teams, Google Chat |
| `renderTemplateWithCustomDataMap(TemplateDefinition, Map)` | Each map key is a top-level variable | Email templates (body, title, daily digest) |
| `renderTemplateWithCustomDataMap(String, Map)` | Inline template string, same binding | Ad-hoc template rendering |

Rules:
- For `renderTemplate`, access data in Qute as `{data.context.*}`, `{data.events.*}`, `{data.source.*}`.
- For `renderTemplateWithCustomDataMap`, each key in the map (e.g. `action`, `environment`, `source`, `pendo_message`, `ignore_user_preferences`) is a top-level Qute variable.
- All render results are **trimmed**. Do not rely on leading/trailing whitespace.

## 7. Template Data Context

### Drawer / Slack / MS Teams / Google Chat (`renderTemplate` path)
The `data` map is a Jackson-serialized `Action` object merged with additional entries:
- `data.context.*` -- action context properties (accessed dynamically via `ActionExtension`)
- `data.events` -- list of events, each with `payload.*` entries
- `data.source.application.display_name`, `data.source.bundle.display_name`, `data.source.event_type.display_name`
- `data.severity` -- optional severity string
- `data.inventory_url`, `data.application_url` -- injected by the caller

### Email (`renderTemplateWithCustomDataMap` path)
Top-level variables:
- `action` -- the Action object or map
- `environment` -- `Environment` bean (provides `url()`, `name()`, `isLocal()`, `isStage()` methods, plus `getUrl()` getter)
- `source` -- same structure as above (`source.application.display_name`, etc.)
- `pendo_message` -- optional marketing/pendo message object
- `ignore_user_preferences` -- boolean controlling footer text

## 8. Template Extensions

Extensions in `com.redhat.cloud.notifications.qute.templates.extensions` add methods callable from Qute:

| Extension class | Methods available in templates |
|---|---|
| `ActionExtension` | `context.propertyName` (dynamic property access on Context), `payload.propertyName`, `action.toPrettyJson()`, `event.toPrettyJson()` |
| `LocalDateTimeExtension` | `.toUtcFormat()`, `.toStringFormat()`, `.toTimeAgo()` -- works on both `LocalDateTime` and `String` |
| `SeverityExtension` | `.severityAsEmailTitle()`, `.toTitleCase()`, `.asSeverityEmoji()`, `.asPatternFlySeverity()` |
| `ErrataSortExtension` | `.sortErrataSecurityArray()`, `.sortErrataArray()` -- sort lists of errata maps |
| `TimeAgoFormatter` | Internal helper used by `LocalDateTimeExtension.toTimeAgo()` |

Rules:
- New `@TemplateExtension` classes are auto-discovered by Qute. No registration needed.
- Use `@TemplateExtension(matchName = TemplateExtension.ANY)` only for dynamic property access patterns (like `ActionExtension`). For named methods, use `@TemplateExtension` on the method.

## 9. Adding a New Template

Checklist for adding a new event-type template:

1. **Create the template file** in the correct `templates/<rootFolder>/<AppFolder>/` directory with the naming convention from Section 2.
2. **Register in the mapping class**: add an `entry(new TemplateDefinition(...), "AppFolder/fileName")` to the appropriate mapping class's `templatesMap`.
3. **Add event type constants** as `static final String` in the mapping class if new.
4. **Create or update tests**: add a test class under `common-template/src/test/java/<integration_type>/` following the existing pattern.
5. **Run the module build** to verify `checkTemplatesConsistency()` passes (`mvn -pl common-template test`).

When adding a new application:
- Create a new subfolder in each applicable root folder (e.g. `drawer/NewApp/`, `email/NewApp/`).
- Add constants in the mapping class: `APP_NAME`, `FOLDER_NAME` (PascalCase with trailing `/`).
- Consider whether a new mapping class is needed (new bundle) or entries belong in an existing one.

## 10. Startup Validation

`TemplateService.init()` runs at `@PostConstruct` and calls `checkTemplatesConsistency()` which:
1. Iterates every `TemplateDefinition` key in the merged `templatesConfigMap`.
2. Verifies the file exists on the classpath via `classLoader.getResource("templates/" + filePath)`.
3. Calls `engine.getTemplate(filePath).instance()` to verify Qute can parse it.
4. Throws `TemplateNotFoundException` on any failure, preventing application startup.

Rule: NEVER merge a PR that adds a mapping entry without the corresponding template file. The application will fail to start.

## 11. Secure Templates

When `notifications.use-secured-email-templates.enabled=true`, `TemplateService` loads **only** `SecureEmailTemplates.templatesMap` instead of all standard mappings. Secure templates:
- Live under `email/Secure/<AppFolder>/`.
- Are a subset of templates (primarily daily digest and OCM email templates).
- Reuse the same `TemplateDefinition` keys, so they override standard templates entirely.

Rule: Do not add non-email integration types to `SecureEmailTemplates`.

## 12. Testing Patterns

Tests are `@QuarkusTest` classes organized by integration type package:

| Package | Pattern |
|---|---|
| `drawer/` | Inject `TestHelpers`, call `testHelpers.renderTemplate(TemplateDefinition, Action)`, assert rendered markdown content with `assertEquals` |
| `email/` | Extend `EmailTemplatesRendererHelper`, override `getApp()`/`getBundle()`, use `generateEmailBody()`/`generateEmailSubject()`/`generateAggregatedEmailBody()`, assert with `assertTrue(result.contains(...))` |
| `slack/`, `ms_teams/`, `google_chat/` | Inject `TestHelpers` and `TemplateService`. Either call `testHelpers.renderTemplate(IntegrationType, eventType, action, inventoryUrl, applicationUrl, useBeta)` for default templates, or create `TemplateDefinition` and call `templateService.renderTemplate(templateConfig, action)` directly. Assert JSON content with `assertTrue`. |

Rules:
- Chat tests may use either the `TestHelpers.renderTemplate` convenience method or call `templateService.renderTemplate` directly after constructing a `TemplateDefinition`.
- Test both beta and non-beta variants using `@ParameterizedTest` with `@MethodSource` or `@ValueSource`.
- Test helpers in `helpers/` package (e.g. `ErrataTestHelpers`, `OcmTestHelpers`) build `Action` objects with realistic data. Reuse or extend these rather than duplicating setup logic.
- Email tests write rendered output to `target/` for manual inspection (controlled by `SHOULD_WRITE_ON_FILE_FOR_DEBUG`).

## 13. Common Qute Patterns

```qute
{! Conditional display !}
{#if data.context.display_name??}...{/if}

{! Iteration with size check !}
{data.events.size()} event{#if data.events.size() > 1}s{/if}

{! Extension method call !}
{data.severity.toTitleCase}
{data.severity.asSeverityEmoji}

{! Template inclusion (used for shared layouts) !}
{#include email/Common/insightsEmailBody}
{#content-body}...{/content-body}
{/include}

{! Insert blocks for composable templates !}
{#insert content-body}{/}
```

Rule: Use `{#include}` for layout inheritance (email body wrapping) and `{#insert}` for overridable sections. Prefer Qute's null-safe operator `??` for optional fields.
