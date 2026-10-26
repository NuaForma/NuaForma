# Extend NuaForma

Extensions let an application supply private behavior without changing NuaForma itself. You do not need to contribute an extension upstream. All installed implementations are trusted executable host code, not sandboxed templates.

## Choose an extension point

| Extension | Use it for | Registration |
| --- | --- | --- |
| Document provider | Your own formats or complete processing implementation | Provider ServiceLoader metadata, or explicit core registration |
| Value pipe | A JSON-compatible transformation with arguments and chaining | Pipe ServiceLoader metadata, or an explicit template registry |
| Content pipe | Structured content inserted by its owning generator | Explicit generator registration, not a global content-pipe ServiceLoader |

Value/content pipes are available in `0.2.0-alpha.1`, with minimum contract `0.2`; see [version status](README.md#source-versus-published-versions). Historical `0.1` artifacts do not contain these APIs. Provider extensions remain governed by their declared contract version and actual runtime dependencies.

## Implement a provider

Implement `DocumentProvider` against `nuaforma-contracts`; core/server/document libraries are not required dependencies of your provider. Format identifiers are extensible tokens, not a closed enum. Declare readable/writable formats and guarantee every combination in their Cartesian product; report executable subsets through availability and validate complete requirements through assessment.

Package both registrations with the implementation in the same JAR:

```text
META-INF/services/dev.nuaforma.contracts.DocumentProvider
META-INF/nuaforma/providers/<binary-implementation-class-name>.properties
```

The [contracts reference](../nuaforma-contracts/README.md#static-provider-metadata-and-versioning) specifies the descriptor fields; the [core guide](../nuaforma-core/README.md#metadata-before-provider-loading) describes origin validation before initialization. Contract compatibility uses same-major/minimum-minor matching, not exact minor matching. Published `0.2.0-alpha.1` uses contract `0.2`, accepting legacy `0.1` providers. Release `0.2.0-alpha.2` uses runtime contract `0.4`, accepting compatible minima `0.1` through `0.4`. DOCX and extensions referencing `RejectionReason` or structured `Diagnostic` overloads require at least `0.3`; the PDF provider's conformance-policy facet requires `0.4`. Older runtimes reject incompatible descriptors before loading. Extensions using only the original pipe/logging/content APIs may still declare `0.2`. Module release versions and required contract versions are separate; selecting a release does not publish it.

The [independent Java provider example](../examples/embedded/README.md#third-party-providers) compiles against contracts only and demonstrates a company-owned format, SPI and explicit registration. Follow the complete contracts for streams, resource limits, concurrency, cancellation, diagnostics and option rejection; the example is not a universal renderer.

## Implement a value pipe

`TemplatePipe` transforms `Any?` into `Any?`, with positional arguments and an explicit locale/time-zone/cancellation context. Inputs/results are recursively JSON-compatible, including nested maps/lists, not arbitrary application objects. Intermediate types survive chaining; the final value must satisfy scalar/null insertion rules.

Package the implementation with its registration resources:

```text
META-INF/services/dev.nuaforma.contracts.TemplatePipe
META-INF/nuaforma/pipes/<binary-implementation-class-name>.properties
```

Use the [implementation/registration reference](../nuaforma-template/README.md#implementation-and-registration) and runnable [independent Kotlin extension](../examples/value-pipes/README.md). Declare arguments, defaults, null behavior and minimum contract `0.2` for this new SPI. Discovery rejects duplicates, reserved names, invalid descriptors and origin conflicts rather than choosing the first classpath entry. Explicit application composition can supply `TemplatePipes.of(...)` instead.

Custom markers use parentheses, for example `{customer | select("address") | select("city")}` with the example's installed `select` pipe. They may chain but are not arbitrary callable functions, `if` conditions or `each` expressions. Existing built-in `number`/`date` formatting keeps its documented syntax.

## Implement generator-owned content

`ContentPipeProvider` exposes a generator's actual content definitions. Its `ContentPipe` implementations return structured `ContentResult` values which that generator must explicitly recognize, bound and insert. A stock DOCX provider does not automatically accept a new content implementation just because a JAR is installed; supply/configure a generator that owns the registration and insertion behavior.

Content markers contain exactly one pipe, without preceding/following chains. Metadata declares supported format/profile combinations, media types, contexts, arguments and null behavior. See the [content facade/grammar](../nuaforma-template/README.md#generator-content-pipes-unreleased-source), [contracts](../nuaforma-contracts/README.md#optional-generator-content-pipes-unreleased-source) and built-in [image pipe](../nuaforma-docx/README.md#image-content-pipe).

Expose metadata through the optional provider facets so capabilities and the [Playground](../nuaforma-playground/README.md) describe the same registrations that execute. Metadata is not proof of current converter availability or full request compatibility.

## Package, install and verify

For broad embedded use, compile against Java 17 and matching contracts. The Java 25 server can load explicitly server-only Java 25 extensions; those are not Java-17-compatible libraries. Keep dependencies non-conflicting: do not bundle duplicate NuaForma/Kotlin/application classes or rely on manifest `Class-Path` downloads.

Embedded applications put JARs on their trusted classpath or configure an explicit loader/registry. Keep a custom loader alive as long as its registrations are used. The source server snapshots read-only operator JARs from `/opt/nuaforma/extensions` at startup; restart after changes. See the [mount guide](../deployment/pdf-runtime/MOUNTS.md) for budgets, conflict checks and deployment paths. REST uploads are document/font resources, never executable extension installation.

The server validates every raw archive entry before inspecting effective multi-release classes. Package canonical relative paths using `/`: no absolute names, backslashes, drive/URI colons, NUL characters, empty path segments or `.`/`..` segments. A directory may have one trailing `/`. Names reject rather than being rewritten; ordinary resources, spaces, Unicode and inner-class `$` names remain supported. Inspection does not extract entry-named files or initialize classes. Installed extensions remain trusted executable code, not a sandbox for untrusted code.

Test the packaged JAR independently: discovery/origin rejection, actual generation, null/type/argument boundaries, concurrent tenants and cooperative cancellation. Providers own processing limits and sanitized errors; callbacks must bound their own work. Review font, layout, profile and barcode fidelity when output is affected. Arbitrary in-process extension code cannot be safely killed or sandboxed by the template language.

Private extensions/dependencies remain the operator's responsibility. Respect upstream licenses and never pass secrets or unrelated request state implicitly. Validators use a fixed classpath and cleared inherited environment without extension JARs; the server and validators share the ordinary container filesystem boundary. Mounted extensions remain trusted code. Contribution to NuaForma itself follows [CONTRIBUTING.md](../CONTRIBUTING.md).
