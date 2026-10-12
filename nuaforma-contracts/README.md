# NuaForma provider contracts

Development API for Kotlin and Java 17, in `dev.nuaforma.contracts`. The unpublished contract baseline remains **0.1** by maintainer decision; no stable release is claimed. This module contains value types, provider contracts, descriptor parsing and validation helpers. Discovery and selection live in [core](../nuaforma-core/README.md). Expression evaluation, DOCX/PDF processing and HTTP serialization belong to implementation modules, not contracts.

## Optional font catalog

Providers may implement `FontCatalogProvider.fontCatalog(GenerationContext)` without changing `DocumentProvider`. `FontCatalog` is a bounded immutable deployment-only snapshot of `AvailableFont` entries: `family`, `face`, `origin` (`BUNDLED`/`MOUNTED`) and optional logical `resource` ID. No paths, font bytes, request uploads or template-private fonts belong in it. Faces are provider metadata, not a closed global enum; initial providers report regular/bold/italic/boldItalic, with variable faces also reported by the renderer. Availability does not establish embedding permissions, glyph coverage or successful generation for every template.

Honor cancellation/deadlines, return at most 2048 entries and fail if enumeration is unavailable. An absent facet means enumeration is unsupported, not that no fonts exist. The new facet stays in the unpublished `0.1` draft; released contracts still follow the agreed compatibility/versioning policy.

## Requests and supplied data

```kotlin
val requirements = GenerationRequirements(
    input = FormatRequest(DocumentFormat.DOCX),
    output = FormatRequest(DocumentFormat.PDF, mapOf("version" to "1.7")),
    options = GenerationOptions(watermark = TextWatermark("CONCEPT")),
)
val request = GenerationRequest(
    template = templateInputStream,
    requirements = requirements,
    data = mapOf("customer" to mapOf("name" to "Example"), "items" to listOf(1, 2)),
)
```

Java uses the same types with constructors and getters:

```java
var requirements = new GenerationRequirements(
    new FormatRequest(DocumentFormat.DOCX),
    new FormatRequest(DocumentFormat.PDF, Map.of("version", "1.7")),
    new GenerationOptions(new TextWatermark("CONCEPT"))
);
var request = new GenerationRequest(templateInputStream, requirements,
    Map.of("customer", Map.of("name", "Example")));
```

These examples describe requests, not supported output. A provider must explicitly accept the format, options and their combination before generation. The Java and Kotlin tests exercise these API shapes.

`data`, input/output `options` and general-option `extensions` are recursively copied into unmodifiable snapshots. Accepted values are string-keyed maps, lists, strings, booleans, null and JVM numbers (`Byte`, `Short`, `Int`, `Long`, `BigInteger`, `BigDecimal`, finite `Float`/`Double`). Arrays, sets, arbitrary domain objects, non-string keys and cycles are rejected. Convert your own objects to this generic representation first; no project-specific business classes are needed. Null differs from an absent map entry. A snapshot allows up to 64 nested levels and 100,000 values, including containers; these construction guards are separate from processing budgets. String lengths and total payload bytes need bounded ingestion by the caller and eventual transport layer.

Every supplied data field is available to the eventual template language, including nested objects and list elements. There are no field/path allowlists. The caller supplies only data the template may use. Expression evaluation must expose **only `data`**, not options, resources, authentication, transport metadata, the context or host internals. These contracts do not themselves implement a sandbox or an expression evaluator. Inserted text must not be recursively evaluated as template syntax.

General options provide a nullable text watermark, explicit locale (`Locale.ROOT` by default), time zone (`UTC`) and a generic extension map. Watermark defaults are RGB `#B0B0B0`, opacity `0.25` and rotation `-45` degrees; omitting it requests no new watermark. Text is nonblank and at most 4096 characters, opacity is finite within 0–1 and rotation is finite within -360–360. Validation of these values does not prove visible watermark support: a provider must accept and implement the request.

Requested watermark text follows the same restricted template language and request `data` as document content, including placeholders, conditions, repetitions and number/date formatting. For example, `CONCEPT — {customer.name}` resolves using the supplied customer object. Locale, time zone and cumulative processing limits are shared; resolve once before placing the watermark or converting the output. Inserted values are never evaluated again. Escaped braces (`{{` and `}}`) produce literal braces. A blank resolved value requests no added watermark. The DOCX module documents its implemented placement and resolved-text limits; PDF and REST support are not implied by these contracts.

The planned REST mapping uses a `request` JSON multipart part plus a binary `template` part. Multipart handling and JSON mapping belong to the server, not this module. No endpoint or JSON serializer is provided here.

## Provider contract

Implement `DocumentProvider`:

- `capabilities()` describes nonempty readable/writable format sets and option definitions. Every read/write combination is a provider obligation; core must not assemble arbitrary readers and writers.
- `availability()` reports currently executable subsets. If nothing works, both sets are empty with an error diagnostic. A missing PDF converter can leave DOCX-to-DOCX available. `validateAgainst(capabilities)` checks that availability does not expand declared support.
- `assess(requirements)` checks the complete requested combination, without template streams or data. It distinguishes supported, unsupported and temporarily unavailable requests. A successful assessment is not a reservation or a guarantee that template content is valid.
- `generate(request, output, context)` rechecks readiness, validates content and options, applies limits, and returns the achieved output settings and warning diagnostics. It throws `GenerationException` for structured failures or `IOException` for I/O failures. Providers must reject explicit unsupported options, never silently downgrade output.

Format identifiers are extensible lowercase tokens such as `docx` or `pdf`, not vendor representation types. `OptionDefinition` describes a name, type, human-readable description, required flag, optional default and allowed values. `defaultValue = null` means **no default**; it does not describe an explicit null default. Required options cannot also supply a nonnull default. Supplied null values remain present in requests and must be accepted or rejected explicitly by the provider. These descriptors are not recursive JSON Schema; conditional requirements and version/profile/font combinations still require provider assessment. `generalOptions` describes the provider's supported general fields and extensions; providers document the corresponding names and validate them against `GenerationOptions`.

Provider code is trusted executable application code, not sandboxed by this interface. All methods must be safe for concurrent calls with isolated per-generation mutable state. These contracts cannot automatically enforce correct provider implementations.

## Streams, resources and budgets

The caller owns the template and output streams and closes them, including after failures. A request's template stream is one-use. Providers must not close these streams. Failures can leave partial output bytes: the API cannot roll back an arbitrary stream. Applications that need atomic output should generate to their own bounded temporary destination and publish only after success.

Optional resources are a map of logical identifiers to `ResourceSource` factories. Identifiers contain letters, digits, dots, underscores or hyphens, start with a letter/digit and are at most 128 characters; they are not paths or URLs. Each factory supplies a fresh stream which the provider closes. Construction does not open resources. No implicit filesystem/network lookup or font handling is implemented; the caller determines resource retrieval and the provider defines supported resource meanings.

Create a fresh `GenerationContext` per operation. Default budgets are 32 MiB template input, 128 MiB output, 256 MiB archive expansion, 10,000 archive entries, expression depth 64, 100,000 repetitions and two minutes. Budgets must be positive; duration is bounded to one day. A supplied earlier deadline shortens the duration budget, never extends it. The duration starts when the context is constructed. Providers regularly call `checkActive()`; it reports cancellation (including thread interruption without clearing the flag) and deadline expiry. Actual byte/archive/expression/repetition enforcement and external-process isolation remain provider/engine responsibilities. A deadline check is cooperative, not a process-killing timeout.

Diagnostic codes are extensible uppercase tokens. Diagnostics contain severity, a sanitized message and optional location, not raw documents or data. Failed assessments and `GenerationException` need at least one error; successful results contain only warnings. Constructor invariant failures use `IllegalArgumentException`; later core/server boundaries must translate these into appropriate diagnostics/API errors.

## Static provider metadata and versioning

Register a provider in the standard service file:

```text
META-INF/services/dev.nuaforma.contracts.DocumentProvider
```

Its contents name the provider's binary implementation class. Add a matching descriptor, for example `META-INF/nuaforma/providers/example.DocxProvider.properties`:

```properties
provider.id=example-docx
provider.class=example.DocxProvider
provider.version=0.1.0
contract.major=0
contract.minor=1
```

`ProviderMetadata.resourcePath(className)` determines the descriptor path. `ProviderMetadata.read(stream)` parses Java properties without loading or initializing any provider class and leaves the caller's stream open. Descriptors are limited to 8192 bytes and exactly the five fields above; duplicate, missing, unknown and malformed fields fail. Provider release versions use SemVer, independently of the required contract version.

Compatibility is directional: `available.accepts(required)` requires the same major and an available minor at least as high as the required minor. Numeric comparison accepts 1.2 under 1.9 and 1.9 under 1.10, but rejects 1.2 under 1.1 or 2.x. `ContractVersion.parse` accepts `major.minor` or `major.minor.patch` and ignores a numeric patch component. Patch/minor releases within a major family must retain source, JVM binary and documented behavioral compatibility; adding required provider methods is a breaking change.

Actual discovery lives in core. It associates metadata with the originating service registration, verifies class identity, rejects missing/invalid/incompatible descriptors **before loading or instantiating provider code**, and applies the same contract/capability/availability checks to explicit registration. This module's metadata reader and initialization-counter test verify parsing without initialization; core adds independently packaged `ServiceLoader` tests. Neither module claims compatibility with independently published historical artifacts that do not exist yet.

## Verification

From the repository root:

```sh
./gradlew :nuaforma-contracts:test
./gradlew build generatePomFileForMavenJavaPublication
```

Tests cover Java/Kotlin use, nested immutable data, option combinations, narrower runtime availability, diagnostics, cancellation, stream ownership, metadata validation and reading without provider initialization. ArchUnit checks production contracts depend only on their own package, the JDK, Kotlin and compiler-generated JetBrains annotations; a deliberately violating fixture verifies the rule catches an external test dependency. Synthetic provider fixtures are not document renderers and do not establish DOCX/PDF fidelity. Discovery integration tests live in core; the [repository architecture suite](../ARCHITECTURE.md#architecture-tests) adds cross-module boundary checks.
