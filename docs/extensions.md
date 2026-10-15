# Extend NuaForma

Extensions let an application supply private behavior without changing NuaForma itself. You do not need to contribute an extension upstream. All installed implementations are trusted executable host code, not sandboxed templates.

## Choose an extension point

| Extension | Use it for | Registration |
| --- | --- | --- |
| Document provider | Your own formats or complete processing implementation | Provider ServiceLoader metadata, or explicit core registration |
| Value pipe | A JSON-compatible transformation with arguments and chaining | Pipe ServiceLoader metadata, or an explicit template registry |
| Content pipe | Structured content inserted by its owning generator | Explicit generator registration, not a global content-pipe ServiceLoader |

Value/content pipes are implemented in current source but absent from the published `0.1.0-alpha.1` libraries and `0.1.0-alpha.3` container. Use locally staged source artifacts until a matching release is available; see [version status](README.md#source-versus-published-versions). Provider extensions remain governed by their declared contract version and actual runtime dependencies.

## Implement a provider

Implement `DocumentProvider` against `nuaforma-contracts`; core/server/document libraries are not required dependencies of your provider. Format identifiers are extensible tokens, not a closed enum. Declare readable/writable formats and guarantee every combination in their Cartesian product; report executable subsets through availability and validate complete requirements through assessment.

Package both registrations with the implementation in the same JAR:

```text
META-INF/services/dev.nuaforma.contracts.DocumentProvider
META-INF/nuaforma/providers/<binary-implementation-class-name>.properties
```

The [contracts reference](../nuaforma-contracts/README.md#static-provider-metadata-and-versioning) specifies the descriptor fields; the [core guide](../nuaforma-core/README.md#metadata-before-provider-loading) describes origin validation before initialization. Contract compatibility uses same-major/minimum-minor matching, not exact minor matching. Release version and required contract version are separate identities.

The [independent Java provider example](../examples/embedded/README.md#third-party-providers) compiles against contracts only and demonstrates a company-owned format, SPI and explicit registration. Follow the complete contracts for streams, resource limits, concurrency, cancellation, diagnostics and option rejection; the example is not a universal renderer.

## Implement a value pipe

`TemplatePipe` transforms `Any?` into `Any?`, with positional arguments and an explicit locale/time-zone/cancellation context. Inputs/results are recursively JSON-compatible, including nested maps/lists, not arbitrary application objects. Intermediate types survive chaining; the final value must satisfy scalar/null insertion rules.

Package the implementation with its registration resources:

```text
META-INF/services/dev.nuaforma.contracts.TemplatePipe
META-INF/nuaforma/pipes/<binary-implementation-class-name>.properties
```

Use the [implementation/registration reference](../nuaforma-template/README.md#implementation-and-registration) and runnable [independent Kotlin extension](../examples/value-pipes/README.md). Declare arguments, defaults, null behavior and a compatible contract version. Discovery rejects duplicates, reserved names, invalid descriptors and origin conflicts rather than choosing the first classpath entry. Explicit application composition can supply `TemplatePipes.of(...)` instead.

Custom markers use parentheses, for example `{customer | select("address") | select("city")}` with the example's installed `select` pipe. They may chain but are not arbitrary callable functions, `if` conditions or `each` expressions. Existing built-in `number`/`date` formatting keeps its documented syntax.

## Implement generator-owned content

`ContentPipeProvider` exposes a generator's actual content definitions. Its `ContentPipe` implementations return structured `ContentResult` values which that generator must explicitly recognize, bound and insert. A stock DOCX provider does not automatically accept a new content implementation just because a JAR is installed; supply/configure a generator that owns the registration and insertion behavior.

Content markers contain exactly one pipe, without preceding/following chains. Metadata declares supported format/profile combinations, media types, contexts, arguments and null behavior. See the [content facade/grammar](../nuaforma-template/README.md#generator-content-pipes-unreleased-source), [contracts](../nuaforma-contracts/README.md#optional-generator-content-pipes-unreleased-source) and built-in [image pipe](../nuaforma-docx/README.md#image-content-pipe-unreleased-source).

Expose metadata through the optional provider facets so capabilities and the [Playground](../nuaforma-playground/README.md) describe the same registrations that execute. Metadata is not proof of current converter availability or full request compatibility.

## Package, install and verify

For broad embedded use, compile against Java 17 and matching contracts. The source Java 25 server can also load server-only Java 25 extensions, but those are not Java-17-compatible libraries. Existing alpha.3 containers run Java 17. Keep non-conflicting dependencies explicit; do not bundle duplicate NuaForma/Kotlin/application classes or rely on manifest `Class-Path` downloads.

Embedded applications put JARs on their trusted classpath or configure an explicit loader/registry. Keep a custom loader alive as long as its registrations are used. The source server snapshots read-only operator JARs from `/opt/nuaforma/extensions` at startup; restart after changes. See the [mount guide](../deployment/pdf-runtime/MOUNTS.md) for budgets, conflict checks and deployment paths. REST uploads are document/font resources, never executable extension installation.

Test the packaged JAR independently: discovery/origin rejection, actual generation, null/type/argument boundaries, concurrent tenants and cooperative cancellation. Providers own processing limits and sanitized errors; callbacks must bound their own work. Review font, layout, profile and barcode fidelity when output is affected. Arbitrary in-process extension code cannot be safely killed or sandboxed by the template language.

Private extensions/dependencies remain the operator's responsibility. Respect upstream licenses, never pass secrets or unrelated request state implicitly, and never add application/extension mounts to the renderer/validator sandbox. Contribution to NuaForma itself follows [CONTRIBUTING.md](../CONTRIBUTING.md).
