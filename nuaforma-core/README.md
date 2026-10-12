# NuaForma core

Development API for discovering, validating and selecting document providers, usable from Kotlin and Java 17. Core knows only [NuaForma contracts](../nuaforma-contracts/README.md), not concrete format modules. It does not contain a DOCX engine, expression evaluator, PDF converter, HTTP server or process sandbox. Optional DOCX and DOCX/PDF providers exist in their own modules; the core tests additionally use synthetic, independently compiled provider JARs. [Standalone consumer examples](../examples/embedded/README.md) verify the published-artifact boundaries locally; no stable release is available yet.

## Discovery and generation

```kotlin
val registry = ProviderRegistry.discover()
val requirements = GenerationRequirements(
    FormatRequest(DocumentFormat.DOCX),
    FormatRequest(DocumentFormat.PDF, mapOf("version" to "1.7")),
)
val selected = registry.select(requirements) // Fails if no match or multiple matches.
```

Supply a class loader explicitly when managing your own plugin classpath. `discover()` otherwise uses the thread context class loader, falling back to the contracts' class loader. Keep that loader open for the catalog's lifetime; core does not own or close it. Discovery instantiates accepted providers once, not once per request. Providers must satisfy the contracts' concurrency and isolation obligations.

`registry.generate(request, output, context)` checks cancellation/deadline, selects a currently ready provider, delegates generation and checks the deadline and resulting output format. The provider must recheck readiness and validate template content, options, limits and effective output settings. Core does not interpret PDF-specific versions or profiles or verify conformance. Unsupported options and combinations must be rejected by the provider's `assess()` implementation. A matching declaration alone is insufficient.

The caller owns and closes the template/output streams. Failed generation may leave partial bytes; use your own bounded staging destination when atomic publication is required. Unexpected provider exceptions become sanitized structured diagnostics, while provider `GenerationException` and I/O failures retain their declared types. Trusted providers remain responsible for sanitized diagnostic content. Fatal JVM errors are not swallowed.

## Metadata before provider loading

Providers use standard `META-INF/services/dev.nuaforma.contracts.DocumentProvider` registration and the static properties descriptor specified in the contracts guide. Core performs a resource-only scan first. It validates descriptor syntax, class identity, descriptor/class/service origin and contract compatibility before passing an accepted registration to Java `ServiceLoader`. The supplied class loader is trusted; this is not protection against a malicious class-loader implementation or hostile installed application code.

The initial implementation supports **local JARs and exploded class directories on the classpath**. Remote service resources are rejected without opening them. JPMS module-layer discovery, executable/nested JAR protocols, application-server virtual filesystems and other custom resource protocols are not supported by this milestone. Duplicate descriptors or shadowed implementation classes are rejected rather than resolved by classpath order. Service files must be valid UTF-8, at most 64 KiB and 1024 distinct entries; at most 1024 service resources are accepted. Properties descriptors retain the contracts' 8192-byte limit.

Metadata, implementation `.class` and SPI registration must originate from the same class-directory root or JAR. The loaded implementation's code source is checked before instantiation as well. Duplicate provider IDs or implementation identities are rejected before their factories run. Providers requiring incompatible contract versions are rejected without class loading or initialization. Compatibility remains directional: same major, available minor at least required minor, ignoring patch level. Core uses `ContractVersion.CURRENT`; callers cannot override the actual runtime contract version through the public API.

Rejected registrations appear in immutable `registry.diagnostics`, while independent valid providers remain usable. Inspect these diagnostics at application startup; an empty catalog is legal, not proof that discovery succeeded. No providers are fetched or installed automatically. Generation data and templates cannot supply provider class names.

## Explicit registration

Java applications can disable discovery and supply factories:

```java
var metadata = new ProviderMetadata(
    "my-provider", "example.MyProvider", "1.0.0", ContractVersion.CURRENT
);
var registry = ProviderRegistry.registered(List.of(
    new ProviderRegistration(metadata, MyProvider::new)
));
```

This example requires your own `example.MyProvider` implementation; it is not shipped with NuaForma. Metadata compatibility and duplicate identities are checked before invoking the factory. The resulting implementation class must match the declared binary class name; capabilities and availability receive the same validation as discovered providers. Supplying a previously constructed instance or a factory that initializes classes eagerly defeats pre-initialization guarantees in the caller's own code. Core cannot undo that initialization. Explicit factories are trusted application wiring, not values from an HTTP request or template.

`discover(classLoader, registrations)` combines discovery with explicit factories. Duplicate identities across both sources fail, rather than implicitly overriding a provider. Runtime availability is reassessed during selection, so an unavailable PDF converter can leave DOCX output independently usable.

## Selection and preference

`select(requirements, preferredProviderId)` filters by the declared base-format pair, current executable pair and the provider's assessment of complete input/output and general options. No match throws `GenerationException` with available format declarations, relevant rejected assessments and discovery/unavailability diagnostics. Multiple ready matches throw `AMBIGUOUS_PROVIDER` with sorted IDs; resource iteration order never chooses a winner.

A preference is strict: an unknown ID or an unsuitable preferred provider fails; it does not fall back to another provider or output format. Provider IDs are application-controlled selection inputs. Selection does not consume the template, reserve a converter or guarantee that later generation will succeed. `RegisteredProvider` exposes the selected metadata, declared capabilities and contracts-only provider handle; calling that handle directly transfers orchestration responsibility to the caller.

## Verification

From the repository root:

```sh
./gradlew :nuaforma-core:test
./gradlew build generatePomFileForMavenJavaPublication
```

Tests compile synthetic Java providers with the JDK compiler against only the contracts and Kotlin standard library, package service/metadata/class resources in real JARs, and load them through separate class loaders. The fixture source and reproducible builder are under `src/test/resources/providers` and `src/test/kotlin`; no generated JARs are checked in. Static initialization markers and class-load tracking verify rejection before loading/initialization. Tests also cover numeric compatibility, malformed and mismatched descriptors, class shadowing, explicit registration, ambiguity, output subfilters, partial availability, concurrent requests, stream ownership, cancellation and invalid provider results.

ArchUnit checks production core dependencies are limited to core, contracts, the JDK, Kotlin and compiler-generated JetBrains annotations; a violating fixture verifies enforcement. The [repository architecture suite](../ARCHITECTURE.md#architecture-tests) adds cross-module direction, internal access, adapter ownership, public signature leakage and cycle checks. Synthetic output is plain fixture bytes, not a valid DOCX/PDF or evidence of visual fidelity. This milestone does not establish binary compatibility with historical published contract artifacts; there are no such releases yet.
