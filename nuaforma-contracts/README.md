# NuaForma provider contracts

Development API for Kotlin and Java 17, in `dev.nuaforma.contracts`. The contract baseline remains **0.1** by maintainer decision; the published alpha is not a stable release, and source-only additions are identified below. This module contains value types, provider contracts, descriptor parsing and validation helpers. Discovery and selection live in [core](../nuaforma-core/README.md). Expression evaluation, DOCX/PDF processing and HTTP serialization belong to implementation modules, not contracts.

## Optional value pipes (unreleased source)

The additive logging contracts (`GenerationLogging`, `GenerationLogSink`, bounded immutable `GenerationLogEvent`, mode/level enums) carry explicit embedded diagnostics/correlation without MDC, SLF4J or a server dependency. Original `GenerationContext` constructors/default JVM descriptors remain unchanged; additive constructors accept logging. Optional `ProviderReadiness` adds contextual availability/assessment without requiring changes to old providers. See the [logging guide](../docs/logging.md) for safe event handling, limits and source-only examples.

`TemplatePipe` is the format-independent, Java-17-compatible `Any? → Any?` value SPI. It accepts an evaluated value, positional arguments and `TemplatePipeContext` (explicit locale/time zone and cooperative `checkActive()`). `TemplatePipeDefinition` holds a bounded name/description, immutable `OptionDefinition` arguments and declared null behavior. Supported values/results are recursively JSON-compatible, not arbitrary JVM objects or `ContentResult`. A final map/list needs explicit conversion before insertion. Implementations must be thread-safe and bound their own work; installed code is trusted host code, not sandboxed by the expression language.

`TemplatePipeMetadata` describes one ServiceLoader class before initialization: `pipe.name`, `pipe.class`, `pipe.version`, `contract.major`, `contract.minor` in `META-INF/nuaforma/pipes/<binary-class-name>.properties`. Compatibility uses the existing same-major/minimum-minor policy; unknown/duplicate keys, invalid releases and unsupported contract requirements fail. Explicit application registration needs no descriptor. Discovery/evaluation belongs to `nuaforma-template`, not contracts or core.

Optional `TemplatePipeProvider.templatePipes()` reports immutable extension definitions from a generator's actual registry snapshot without adding a required `DocumentProvider` method. This excludes fixed built-in `number`/`date` formatter definitions; names remain reserved. Source capabilities expose this metadata through #60. Existing interfaces and constructors retain their JVM descriptors. See the [language/registration guide](../nuaforma-template/README.md#extensible-value-pipes-unreleased-source) and [independent extension example](../examples/value-pipes/README.md). Published alpha artifacts remain unchanged.

## Optional font catalog

Providers may implement `FontCatalogProvider.fontCatalog(GenerationContext)` without changing `DocumentProvider`. `FontCatalog` is a bounded immutable deployment-only snapshot of `AvailableFont` entries: `family`, `face`, `origin` (`BUNDLED`/`MOUNTED`) and optional logical `resource` ID. No paths, font bytes, request uploads or template-private fonts belong in it. Faces are provider metadata, not a closed global enum; initial providers report regular/bold/italic/boldItalic, with variable faces also reported by the renderer. Availability does not establish embedding permissions, glyph coverage or successful generation for every template.

Honor cancellation/deadlines, return at most 2048 entries and fail if enumeration is unavailable. An absent facet means enumeration is unsupported, not that no fonts exist. The facet uses the `0.1` contract baseline; source additions and released contracts follow the agreed compatibility/versioning policy.

## Optional generator content pipes (unreleased source)

`ContentPipeProvider` is an optional provider facet; it adds no mandatory `DocumentProvider` method and changes no existing constructor or interface signature. It exposes immutable `ContentPipeDefinition` metadata owned by that generator: stable name/description, positional `OptionDefinition` arguments, supported input/output/profile combinations, explicit null behavior and inline insertion context. `chainable` is always false. Empty profile sets mean every profile supported by that generator for the declared pair, not arbitrary formats/profiles outside its own capabilities. Declaration does not establish runtime converter readiness.

`ContentPipe.apply(value, arguments, context)` receives JSON-compatible values and returns trusted generator-owned structured content through the open `ContentResult` interface. Standard immutable results are `ContentResult.Text` and `ContentResult.Empty`. Independent generators may define their own structured result types without changing a closed result hierarchy; the owning generator must recognize, bound and insert those types explicitly. They are not JSON value-pipe results or an implicit escape for host/document-library objects. The template layer transports results without reflection, stringification or interpreting implementation-specific fields. Built-in DOCX rejects unknown result types with a sanitized error before output publication. `ContentPipeContext` provides explicit locale/time zone and `checkActive()`, with no implicit request, resources, transport metadata or unrelated data. Implementations are trusted host code and must be thread-safe, bounded and cooperatively cancellable. Providers decide which implementations to register; templates cannot download or load them. Existing ServiceLoader provider discovery and pre-construction compatibility/origin checks remain the registration boundary; no global content-pipe ServiceLoader is introduced.

`ContentPipeDefinition.contentTypes` is an additive immutable set of accepted source media types, empty for implementations that declare none. The image registration declares `image/png` and `image/jpeg`; this is descriptive metadata, never MIME-based trust/dispatch or a universal content model. The existing constructors, including Kotlin default-argument descriptors, remain intact; the explicit six-argument constructor adds source media declarations without changing previous callers.

The new contracts retain Java 17, the `0.1` contract baseline and existing source/JVM compatibility. Independent generators can implement them using contracts only; generators need not use NuaForma's template language or DOCX provider. The [template facade](../nuaforma-template/README.md#generator-content-pipes-unreleased-source) handles source evaluation and spans; the generator inserts the result in its own model. This is distinct from the chainable JSON-compatible value-pipe SPI (#56). Source capabilities integration (#60) reuses the optional metadata facets without adding mandatory provider methods. Published alpha artifacts do not yet include these contracts.

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

`data`, input/output `options` and general-option `extensions` are recursively copied into unmodifiable snapshots. Accepted values are string-keyed maps, lists, strings, booleans, null and JVM numbers (`Byte`, `Short`, `Int`, `Long`, `BigInteger`, `BigDecimal`, finite `Float`/`Double`). Arrays, sets, arbitrary domain objects, non-string keys and cycles are rejected. Convert your own objects to this generic representation first; no project-specific business classes are needed. Null differs from an absent map entry. A snapshot allows up to 64 nested levels and 100,000 values, including containers; these construction guards are separate from processing budgets. String lengths and total payload bytes need bounded ingestion by the caller and transport layer.

Every supplied data field is available to the template language, including nested objects and list elements. There are no field/path allowlists. The caller supplies only data the template may use. Expression evaluation must expose **only `data`**, not options, resources, authentication, transport metadata, the context or host internals. These contracts do not themselves implement a sandbox or an expression evaluator. Inserted text must not be recursively evaluated as template syntax.

General options provide a nullable text watermark, explicit locale (`Locale.ROOT` by default), time zone (`UTC`) and a generic extension map. Watermark defaults are RGB `#B0B0B0`, opacity `0.25` and rotation `-45` degrees; omitting it requests no new watermark. Text is nonblank and at most 4096 characters, opacity is finite within 0–1 and rotation is finite within -360–360. Validation of these values does not prove visible watermark support: a provider must accept and implement the request.

Requested watermark text follows the same restricted template language and request `data` as document content, including placeholders, conditions, repetitions and number/date formatting. For example, `CONCEPT — {customer.name}` resolves using the supplied customer object. Locale, time zone and cumulative processing limits are shared; resolve once before placing the watermark or converting the output. Inserted values are never evaluated again. Escaped braces (`{{` and `}}`) produce literal braces. A blank resolved value requests no added watermark. The DOCX module documents its implemented placement and resolved-text limits; PDF and REST support are not implied by these contracts.

The implemented [REST mapping](../nuaforma-server/README.md#multipart-and-resources) uses a `request` JSON multipart part plus a binary `template` part and optional resource parts. Multipart handling and JSON mapping belong to the server, not this module. No endpoint or JSON serializer is provided by contracts.

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

Diagnostic codes are extensible uppercase tokens. Diagnostics contain severity, a sanitized message and optional location, not raw documents or data. Failed assessments and `GenerationException` need at least one error; successful results contain only warnings. Constructor invariant failures use `IllegalArgumentException`; core/server boundaries translate these into appropriate diagnostics/API errors.

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

Actual discovery lives in core. It associates metadata with the originating service registration, verifies class identity, rejects missing/invalid/incompatible descriptors **before loading or instantiating provider code**, and applies the same contract/capability/availability checks to explicit registration. This module's metadata reader and initialization-counter test verify parsing without initialization; core adds independently packaged `ServiceLoader` tests. The independent fixtures verify the documented discovery behavior; they are not a substitute for compatibility checks against actual released artifacts.

## Verification

From the repository root:

```sh
./gradlew :nuaforma-contracts:test
./gradlew build generatePomFileForMavenJavaPublication
```

Tests cover Java/Kotlin use, nested immutable data, option combinations, narrower runtime availability, diagnostics, cancellation, stream ownership, metadata validation and reading without provider initialization. ArchUnit checks production contracts depend only on their own package, the JDK, Kotlin and compiler-generated JetBrains annotations; a deliberately violating fixture verifies the rule catches an external test dependency. Synthetic provider fixtures are not document renderers and do not establish DOCX/PDF fidelity. Discovery integration tests live in core; the [repository architecture suite](../ARCHITECTURE.md#architecture-tests) adds cross-module boundary checks.
