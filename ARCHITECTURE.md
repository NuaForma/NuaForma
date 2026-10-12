# NuaForma — Architecture

NuaForma is an embeddable Kotlin/JVM document-generation engine with optional format modules and a thin REST service. This document is the source of truth for agreed architecture decisions and requirements; [AGENTS.md](AGENTS.md) contains agent workflow and development instructions.

The Gradle build, initial public provider contracts, core provider discovery/selection, registered DOCX and optional DOCX/PDF providers, a separate round-trip facade, a format-independent template language and bounded Ktor REST transport exist. The [contracts guide](nuaforma-contracts/README.md), [core guide](nuaforma-core/README.md), [DOCX guide](nuaforma-docx/README.md), [PDF guide](nuaforma-docx-pdf/README.md), [template language specification](nuaforma-template/README.md) and [server guide](nuaforma-server/README.md) describe the development APIs and limitations. Word processing reuses the approved additive `TemplateSession` facade for structural scopes and shared budgets; existing provider and compiled-text APIs remain unchanged. Requested DOCX/PDF watermarks and REST-to-engine parity are implemented and qualified. OpenAPI downloads and final container packaging remain planned. The remaining decisions describe intended product behavior. Implement only the requested milestone and validate capabilities before documenting them as available. Workstation-specific instructions do not belong here.

## Product direction and scope

- Initial implementation: Kotlin and docx4j for DOCX templates and DOCX output, supporting placeholders, conditions and repetitions while preserving Word formatting.
- Template conditions must work inside a sentence as well as across complete paragraphs or sections. Repetitions must support collections and table rows, including nested structures.
- Input data consists of generic JSON-compatible objects, lists and scalar values. Consumers define their own nested data structures; never require project-specific customer or business domain classes.
- The engine must be usable directly from Kotlin and Java applications without running the REST service, a website or Docker.
- The REST service accepts multipart data with a `request` JSON part and a separate binary `template` part, plus optional `resource.<id>` binary parts. Keep this transport mapping outside the embedded contracts. The development `/api/v1/documents`, `/api/v1/assess` and `/api/v1/capabilities` endpoints, serialization, limits and safe diagnostics are specified in the [server guide](nuaforma-server/README.md); they use the existing engine contracts. No public deployment or stable API is implied.
- Input and output requests each carry a format identifier and optional generic JSON-compatible `options`. Providers describe and validate their own options, including version/profile combinations; core need not know format-specific fields. Keep general generation options and template `data` separate. Reject unknown or invalid explicit options instead of silently ignoring or downgrading them.
- The REST API specification must be downloadable in both JSON and YAML. Both representations must describe the same contract.
- DOCX-to-DOCX and DOCX-to-PDF are both part of the early product scope. PDF output requires a separately selected and verified conversion backend; it is not implicitly supported by implementing DOCX. XLSX, ODT and ODS remain future extensions. ODT is a text-document format; ODS is a spreadsheet format. Document supported input/output combinations explicitly.
- Optional, configurable text watermarks such as `CONCEPT` belong to the early DOCX and PDF scope. Consumers can request the text, color, opacity and rotation through generation options; omitting the option adds no watermark. Exact field names and defaults are not yet stable contracts.
- Future PDF support includes selectable output profiles, user-provided fonts and, where supported, font embedding. Validate conformance before claiming PDF/A or another PDF profile.
- Future signable PDF forms require empty digital-signature fields that another application can sign. NuaForma does not itself manage signing identities, certificates or private keys, or apply signatures.
- Storage and database integrations are optional adapters. Direct engine users must be able to supply all inputs themselves without configuring a database.
- The project license is Apache-2.0. Commercial use, internal use and private modifications are intended use cases.
- LibreOffice is the approved early DOCX-to-PDF backend (decision 2026-10-12). It remains a separate executable behind the optional PDF adapter; neither Microsoft Word nor Euro-Office is a production dependency. Backend selection does not establish an implemented provider or a qualified Linux deployment. Preserve the approved conversion-copy scope and issue #32 limitation, and verify the actual isolated runtime before exposing PDF availability.
- LibreOffice and bundled components retain their own licenses. The user approved the additional redistribution obligations; follow the [LibreOffice distribution checklist](docs/libreoffice-licensing.md) for the exact packages and images distributed. NuaForma's independently written engine remains Apache-2.0. Do not publish a bundled renderer/image until its notices, corresponding source availability and component-specific obligations have been verified.
- The approved deployment direction bundles LibreOffice in the same container as NuaForma, not in separate worker containers. The PDF adapter starts a per-generation conversion process with its own writable files/profile. Bubblewrap is approved as a separately executed LGPL-2.1-or-later runtime helper; preserve its license, notices and corresponding source provision. Mount only a reviewed, immutable renderer filesystem and that request's resources into its sandbox, never the application filesystem, secrets, sibling requests or a Docker socket. Deny network and inherited environment access and bound execution/concurrency. Check sandbox readiness; if required isolation is unavailable, keep PDF unavailable rather than running LibreOffice without it. DOCX-only embedded use remains independent of both executables and Docker.

Do not expand the implementation scope merely because a capability appears in this section. Implement the requested milestone; document limitations and ask before materially expanding it.

## Repository and module organization

- Develop NuaForma in one repository with a Gradle multi-project build. The product modules are Gradle subprojects, not separate Git repositories.
- Library modules are separately publishable artifacts. Consumers select the contracts, engine and format modules they need; using a single repository must not require depending on every module or the server.
- Changes to contracts, core and providers can be developed and verified together in one repository change. Separate publication does not imply a separate repository or an independently chosen release cadence for every module.
- Keep AGENTS.md and ARCHITECTURE.md as shared root documents. Modules may add narrowly scoped agent instructions when needed, but must reference the central architecture rather than maintain duplicate copies. AGENTS.local.md remains local and ignored by Git.
- Third-party providers may be developed in external repositories against the published contracts. Moving NuaForma's own modules to separate repositories is a future architectural decision requiring explicit approval, not part of the initial setup.

## Module and dependency boundaries

The six Gradle subprojects are `nuaforma-contracts`, `nuaforma-core`, `nuaforma-template`, `nuaforma-docx`, `nuaforma-docx-pdf` and `nuaforma-server`. Public contracts and core provider discovery/selection remain development drafts. The DOCX provider implements DOCX-to-DOCX; the optional PDF provider implements DOCX-to-DOCX and qualified DOCX-to-PDF, with runtime availability reporting. Capabilities must not be advertised as available until implemented and verified.

- `nuaforma-contracts`: separately published public interfaces and shared value types for providers, formats, generation options, results and diagnostics. Use the Kotlin/Java package `dev.nuaforma.contracts`. Keep it independent of core, server and concrete format implementations.
- `nuaforma-core`: the format-independent engine that discovers providers, checks their compatibility and availability, selects a suitable provider and orchestrates generation through the contracts. No dependency on concrete format modules, HTTP frameworks, persistence systems or docx4j representation types.
- `nuaforma-template`: separately publishable, format-independent restricted language compiler and evaluator under `dev.nuaforma.template`. Depends only on contracts and the platform, not core, server or a document library. Format modules may reuse its public API without pulling in docx4j; processing code does not belong in contracts. Core remains responsible for orchestration, not expression evaluation. Compiled templates render text; the additive session facade supplies source-aligned fragments and structural scopes. Native Word editing belongs in the DOCX adapter, which now depends on this public facade.
- `nuaforma-docx`: DOCX parsing, transformation and writing. docx4j and OOXML representation types stay behind this adapter's implementation boundary.
- `nuaforma-docx-pdf`: reads DOCX and writes both DOCX and PDF. Reuse `nuaforma-docx` internally and add conversion; do not create a second template engine. Conversion may require an intermediate DOCX and an external renderer, depending on the selected backend.
- `nuaforma-server`: HTTP transport, request validation, upload/download handling, authentication integration and invocation of the engine. Do not implement a second transformation pipeline here.
- Publish format modules around usable document-processing capabilities. Separate parsing, transformation, writing and conversion responsibilities internally; do not require a separately published parser and writer artifact for every responsibility.
- Future format, conversion and storage adapters must be optional. A DOCX-only consumer must not pull in a PDF renderer, spreadsheet stack, database client or server framework.
- Core and format providers depend on `nuaforma-contracts`. Third-party providers must be implementable with only the contracts dependency, without depending on core. The server uses core; format providers may reuse implementation components from other format modules, but must not depend on core or server. Avoid circular module dependencies.
- Format modules may depend on `nuaforma-template`, but are not required to use this language. The template module cannot depend on concrete providers. The server includes it at runtime in the development module inventory but does not implement a second evaluation path.

**Ask, do not inspect:** expose format-independent questions and results through the interfaces and value types in `nuaforma-contracts`. Do not require core or server code to inspect docx4j objects, JAXB classes, DOM nodes or a renderer's private model to make generation decisions. Public engine APIs must not leak those implementation types.

## Facades and external component adapters

- Access external components through NuaForma-owned facade contracts. Other application code depends on these abstractions, not on the external component's concrete classes or API.
- Place external component implementations consistently under an `adapters` package, with a component-specific subpackage such as `dev.nuaforma.docx.adapters.docx4j`. Do not alternate between `adapter` and `adapters` naming.
- Encapsulate each integration in one clearly bounded adapter layer so replacing a library changes that layer and its registration or wiring, not the document-processing code throughout the project. One layer may contain several focused classes; do not create a single oversized facade class.
- Define facades around the operations NuaForma needs. Do not copy an external library's entire API or build a universal document model merely to wrap it.
- Keep external implementation types inside their owning adapter. Do not leak them through facade methods, constructors, fields, return types, generic parameters, inheritance or public contracts, including when indirectly wrapped in a collection.
- Only the adapter assigned to a component may depend on that component's types or call its constructors and methods. A generic `adapters` package is not permission to use every external dependency from every adapter.
- Internal code may call its own implementation classes normally. Communication across component boundaries uses explicitly allowed public APIs or facade contracts, not another component's internal packages. Preserve the agreed DOCX reuse boundary for `nuaforma-docx-pdf`.
- Select providers through discovery or explicit registration. Core must not import, construct or hard-code a concrete document provider; keep implementation wiring at the designated composition boundary.
- JDK and Kotlin standard-library types and NuaForma's public contract types are not subject to the external-component restriction. Any additional exception must be explicit, narrowly scoped and approved, not an implicit exemption for an entire dependency.

## Provider discovery and supported formats

- Use Java's standard `ServiceLoader` to discover provider implementations installed on the application's classpath. Providers implement the interfaces published in `nuaforma-contracts`; core must not maintain a hard-coded list of format implementations.
- Also allow explicit provider registration so embedded applications can control which implementations participate. Apply the same compatibility, capability and availability checks to discovered and explicitly registered providers.
- Each provider declares two specific capabilities: the formats it can read and the formats it can write. The provider guarantees that it can execute **every combination** of its declared read and write formats; this is the Cartesian-product contract, not two independent lists of unrelated capabilities.
- For example, `nuaforma-docx` reads DOCX and writes DOCX. `nuaforma-docx-pdf` reads DOCX and writes DOCX and PDF, so it must support both DOCX-to-DOCX and DOCX-to-PDF. Do not describe the latter as supporting only PDF output.
- Match a request when one compatible, ready provider can read the requested input format and write the requested output format. The selected provider owns the complete processing operation and may delegate to internal components. Core must not assume that arbitrary readers and writers from different providers can be combined.
- Classpath presence means discoverable, not necessarily ready. Providers must report runtime availability and actionable reasons for unavailable capabilities, such as an absent or unconfigured PDF converter. Distinguish declared support from executable options; advertise usable read/write sets only when every combination within those sets is executable.
- Providers assess complete input/output and general-option requirements without consuming document streams or template data. Declared options describe names, types, requirements, defaults and allowed values; individual field descriptions do not replace validation of combined requirements. Generation must recheck readiness and validate actual content because assessment is not a reservation or a document-validity guarantee.
- A missing converter must not prevent a provider's independently usable DOCX-to-DOCX operation. Availability can narrow the executable sets without changing the declared contract; do not silently substitute another output format.
- No compatible and ready match means a clear failure identifying available options and relevant unavailability reasons. Multiple matches require an explicit provider preference or a clear ambiguity error; never select by `ServiceLoader` or classpath iteration order.
- Discovery is extensibility, not a sandbox: installing a provider installs executable application code. Untrusted templates and data must not choose arbitrary classes or cause providers to be downloaded or installed.

## Module and contract versioning

- Use `major.minor.patch` release versioning. Patch releases contain compatible bug fixes, security fixes and internal improvements; minor releases add backward-compatible functionality; breaking changes always require a major release, regardless of their size. A large internal refactor alone does not require a major release when public contracts and documented behavior remain compatible.
- Distinguish a module's own release version from the contract version it requires; these versions need not be equal. Compatibility checks concern contract versions, not the module's or server's release version.
- Each provider must declare its required contract `major.minor`: the major identifies the compatibility family and the minor identifies the minimum required contract release. Core checks this against the available contract version before using the provider.
- A provider is contract-compatible when `available.major == required.major` and `available.minor >= required.minor`. Ignore patch levels; do not require exact minor matching or compare version strings lexicographically.
- For example, a provider requiring contract 1.2 works with 1.2.0, 1.2.7 and later 1.x releases such as 1.9. It does not work with 1.0 or 1.1 and is not automatically accepted with 2.x. Compatibility is directional: a provider requiring 1.3 cannot run with contract 1.2.
- Both patch and minor contract releases must preserve source compatibility, JVM binary compatibility and documented contract semantics for existing consumers and provider implementations throughout the same major family. Minor releases may add optional functionality, but must not add required interface methods or other new mandatory obligations for existing providers. Breaking changes require a new contract major.
- Never silently accept a different contract major or an available minor below the provider's required minimum. Any future cross-major support requires an explicit compatibility policy and tests.
- Expose the declared contract version as metadata that can be read **before provider instantiation**, not only through an instance method. Check compatibility before instantiating an incompatible provider; do not assume that catching a failure after construction is sufficient.
- Design version metadata and discovery together: obtaining metadata must not initialize provider code. Identify malformed, missing or incompatible metadata with clear diagnostics; do not treat missing metadata as an automatic match.
- The development descriptor is `META-INF/nuaforma/providers/<binary-implementation-class-name>.properties`, separate from standard `META-INF/services/dev.nuaforma.contracts.DocumentProvider` registration. It declares provider ID, implementation class, provider release version and required contract major/minor. Discovery must verify descriptor origin and class identity against the service registration before loading or instantiating provider code. The initial core implementation supports local classpath JARs and class directories; other resource protocols and JPMS module-layer discovery are not supported yet.
- Keep existing JVM provider interfaces and their contracts compatible throughout a contract's major family. Version metadata complements, but does not replace, source and binary compatibility checks and tests using independently built provider artifacts.

## Engine API and lifecycle

- Treat embedded use as a first-class product, not a shortcut through server internals. Keep the public API practical from both Kotlin and Java.
- Accept caller-supplied template input, generic structured data, options and output destinations. Make stream ownership, closing behavior and temporary-file lifetime explicit.
- No hidden network access, database lookup, telemetry or server startup in the engine. External retrieval must be an explicit caller action or configured adapter.
- Each generation has isolated mutable state. Concurrent requests must not share document models, variable bindings, warnings, temporary paths or mutable rendering state.
- Immutable compiled templates may be reused only when their thread safety is verified. Cache identity must account for template content and relevant options; never mix tenant data through caches.
- Apply the same validation and resource policies in embedded and REST use. Transport-specific validation may add restrictions, but must not be the only protection.
- Define resource limits, cancellation behavior and cleanup. Avoid unbounded buffering of templates, expanded documents or output.
- Publish output only after successful generation and validation. Do not overwrite a template or leave a success-shaped partial result after failure. Define transactional behavior for caller-owned streams rather than promising rollback that a stream cannot provide.
- Return structured, actionable diagnostics. Library code must not terminate the host process or force a global logging configuration.

## Template language and data

- Treat the expression language as a deliberately restricted document language, not arbitrary Kotlin, Java, OGNL or script execution.
- Allow only explicitly specified operations. No reflection, class loading, constructor calls, unrestricted method invocation, filesystem access, network access or runtime evaluation of user code.
- Model scopes explicitly for nested objects, collection aliases, conditions and repetitions. Do not mutate caller data during rendering.
- All supplied `data` fields, including nested objects and collection elements, are accessible to placeholders, conditions and repetitions. Do not introduce field allowlists, restricted data paths or per-field access policies. The caller is responsible for supplying only data the template is permitted to use and potentially include in the generated document.
- Full access to supplied `data` does not grant access to authentication details, transport metadata, other requests or host resources. Keep the expression-language operation restrictions and request isolation in force.
- Define and test missing values, explicit nulls, empty collections, type mismatches and formatting rules. Do not silently coerce an object or list into printable text.
- Distinguish literal braces in inserted data from template syntax. Inserted values must not become new expressions through accidental recursive evaluation.
- Keep template authors' syntax understandable. Conditions must support complete sentences without forcing paragraph breaks or changing the formatting of unaffected text.
- Put dates, number formats, locale and time zone under explicit policy. Do not use the host machine's locale or time zone as an undocumented document contract.
- Document stable syntax from its specification and executable examples. Do not let the server and embedded engine accept different languages.

The development language is specified in [nuaforma-template/README.md](nuaforma-template/README.md), with executable examples in that module's tests. It uses `{path}`, `{#if expression}...{#else}...{/if}` and `{#each collection as alias}...{/each}`. Explicit boolean/scalar semantics, `??` fallbacks, quoted object keys, short-circuit operators and bounded number/date formatting do not expose application methods. Format modules must preserve the language's literal-data, scope and failure semantics when applying it to document structures. No DOCX transformation support is implied by a text-language test.

## Document fidelity — the primary generation rule

The template owns the layout. Replace or structurally expand the intended content; do not reconstruct the entire document from extracted plain text.

- Preserve unaffected paragraph and run formatting, styles, numbering, tables, section properties, margins, headers, footers, fields, links, images and package relationships.
- Placeholders may be split across Word runs. Resolve them without losing surrounding text or formatting. Define which formatting replacement text inherits when a placeholder spans differently formatted runs.
- Conditions and repetitions must produce valid document structure. Deleting a block must not leave invalid table cells, stray marker paragraphs or unintended blank pages.
- Repeated content must handle identifiers and relationships correctly. Never blindly clone bookmarks, drawings, comments or other objects with unique IDs.
- Placeholder traversal must include the body, headers, footers and supported text boxes, including legacy VML and modern DrawingML forms and markers split across runs. Test each explicitly. A barcode placed in a rotated text box in the margin is a relevant layout case; do not claim replacement or scanability support until its placeholders and placement are verified.
- Barcode fonts are ordinary formatted text from the replacement engine's perspective. Preserve their font and size; valid barcode encoding, font availability, quiet zones and scanability remain separate requirements. Do not automatically replace a font-based barcode with an image.
- Preserve unaffected package parts byte-for-byte where practical. For rewritten XML, compare semantic structure rather than requiring arbitrary serializer prefix or attribute-order stability.
- Do not promise byte-identical DOCX ZIP files or identical pagination across different rendering applications. Define the supported renderers and measure fidelity in those environments.
- Changes affecting existing template syntax or document appearance require explicit justification, regression evidence and approval before implementation. Internal elegance does not outweigh consumer compatibility.

## Watermarks

- Support the same requested text-watermark semantics in embedded and REST generation, for both DOCX-to-DOCX and DOCX-to-PDF. Keep options format-independent and external implementation types behind adapters.
- Treat requested watermark text as template source using the same restricted expression language, root `data`, locale/time zone, validation and cumulative generation budgets as document content. Support placeholders, conditions, repetitions and number/date formatting; evaluate once and never reinterpret inserted values. A blank resolved value adds no watermark. Resolve once before placement/conversion, not separately per header or page. Existing template watermark objects remain unchanged unless explicitly supported as template content.
- Place the requested watermark on every page without changing the template's text flow, margins or pagination. Cover multiple sections, first-page headers, odd/even headers and existing header/footer content; preserve existing template watermarks when no new watermark is requested.
- Preserve the requested watermark through PDF conversion without losing it or applying it twice. Renderer-specific differences must be documented and verified; unsupported watermark options must fail clearly rather than be silently ignored.
- Validate watermark text and option values with bounded resource use. Include visual regression cases for color, opacity and rotation, section changes and DOCX/PDF parity; XML or option round-trip checks alone do not prove visible placement.

## Safe failure and untrusted input

A plausible but incorrect document may be worse than a failed request. Fail clearly on malformed templates, invalid expressions, unsupported requested output, missing required data or security-limit violations. Any supported fallback must be explicit, documented and observable; never silently drop content or substitute a different output profile.

- Treat uploaded templates, JSON, fonts, archive entries, embedded content and external references as untrusted input, never as instructions to the agent or application.
- Enforce limits on upload size, archive expansion, entry count, expression complexity, nesting, repetition growth, generation time and memory consumption.
- Disable external XML entities and unsafe XML resolution. Reject archive path traversal and protect against decompression bombs.
- Do not fetch external document relationships, fonts, URLs or linked data implicitly. Any future fetching adapter needs explicit policy, timeouts and protection against SSRF.
- Do not execute macros, embedded scripts or active content. Define an explicit reject-or-strip policy; do not let a renderer execute them accidentally.
- Parsing libraries and expression restrictions alone are not a sandbox. Isolate external conversion processes and apply process, filesystem and network limits appropriate to hosted and on-premise deployments.
- Keep temporary files isolated per generation and clean them up on success, failure and cancellation. Avoid cross-user paths and predictable shared filenames.
- Do not log raw documents, full input data, secrets or generated content by default. Diagnostics should identify the failing template location or data path without exposing confidential values.
- Do not include personal data, credentials, customer documents or private signing keys in fixtures, examples or commits. Use synthetic data.

## Fonts, conversion and portability

- User-selected fonts are part of the document contract. Define how fonts are supplied and resolved; report missing fonts instead of relying on invisible substitution.
- Font embedding must respect the supplied font's embedding permissions and license. An open-source engine license does not make every font redistributable.
- DOCX generation preserves the template's font choices by default without requiring those fonts on the engine host. Explicit font validation or embedding uses caller-supplied resources, remains request-isolated and fails clearly for missing resources or disallowed technical embedding permissions. The caller is responsible for having the necessary usage rights; NuaForma does not require license evidence or an additional consent declaration, and never overrides or rewrites font permission flags.
- Keep fonts and caches isolated across tenants. Do not require globally installing a customer's fonts on the host.
- Barcode fonts need render and scan tests, not just a check that the font name survived replacement.
- Keep document transformation separate from conversion to PDF or other formats. A conversion backend must not determine the public engine data model.
- An explicit PDF compatibility option may supply caller-selected paragraph defaults missing from the template, only in an isolated conversion copy. Existing template defaults, alternative spacing modes and paragraph/style overrides remain leading. Without the option, do not infer defaults from the workstation or change the returned DOCX. Word is not a runtime dependency. The qualified development option `paragraphDefaults` and its supported profile are defined in the PDF module guide; research helpers are not public engine APIs.
- The measured LibreOffice compatibility experiment has a temporarily accepted 1.69–1.70-point vertical difference for modern inline text boxes, tracked in [issue #32](https://github.com/NuaForma/NuaForma/issues/32). This is a scoped known limitation, not a universal renderer tolerance or permission to ignore other layout defects. Preserve source/DOCX output and keep barcode, watermark, pagination and unsupported-profile checks in place. Backend approval and production isolation remain separate requirements.
- Production and Docker workflows must not require Microsoft Office, a desktop session or a commercial conversion service. Clearly separate optional interoperability checks from runtime dependencies.
- Claim a PDF profile, embedded-font capability, fillable field or digital-signature field only after testing the actual resulting file with suitable validators or readers.

## Architecture tests

The repository-wide suite is test-only code under `nuaforma-server/src/test/kotlin/dev/nuaforma/architecture`, because the server test runtime already includes all six production modules. Gradle supplies each module's actual main class directories, tracks them as test inputs and builds all production classes before testing. The suite verifies module/package ownership, dependency direction, cross-component public API access, external adapter ownership, public signature leakage and component cycles. Empty scaffolds are explicitly included in the module inventory; contracts/core/template imports must not be empty. No test dependency is added to production artifacts.

Kotlin visibility is read without class initialization using the existing Kotlin reflection component at test scope. JVM-public Kotlin `internal` types are not public component APIs. `internal`, `adapters` and core's `discovery` packages remain implementation boundaries even when their JVM classes are public. Compiler-generated file facades and synthetic holders are not exported entry points; introducing top-level cross-component APIs needs an explicit policy decision rather than a blanket exception.

External dependencies are denied by default. The namespace policy assigns `org.docx4j` and `jakarta.xml.bind` exclusively to `dev.nuaforma.docx.adapters.docx4j`; these integrations implement the DOCX facade and template processing. The approved PDFBox integration owns `org.apache.pdfbox` exclusively under `dev.nuaforma.docx.pdf.adapters.pdfbox`. PDFBox is an implementation dependency of the optional PDF module only. Structural output validation executes in a separately bounded and sandboxed child JVM within the same container: the engine JVM must not parse renderer-produced PDFs because PDFBox does not guarantee bounded CPU, memory or recursion for malformed documents. No additional worker container is required. Approved JDK exceptions are Java 17's `javax.xml.stream` namespace for secure XML inspection and the single `javax.xml.namespace.QName` type for distinguishing JAXB element names. Neither is a blanket exemption for other XML packages or `javax.*`. Any later external library needs an explicit namespace-to-owner mapping and dependency review. JDK and Kotlin platform types remain allowed; JetBrains `NotNull`/`Nullable` are allowed only as compiler annotation dependencies, not arbitrary API types.

The same ArchUnit conditions run against independently compiled synthetic Java fixtures with passing and deliberately violating examples. Public signatures include fields, constructors, parameters, return types, exceptions, inheritance, interfaces and nested generic/array/bound types. Static bytecode checks do not establish reflection safety, renderer isolation, behavioral contract compatibility or document fidelity; keep the integration and visual tests described below.

- Run ArchUnit rules as part of the normal test/build workflow. Inspect production classes across module boundaries, not only the classes of one module in isolation.
- Enforce the agreed dependency direction: contracts know no core, server or format implementations; core knows contracts but no concrete providers; format providers know contracts but no core or server; server uses the public engine API rather than internal processing packages.
- Prevent cyclic dependencies and access to another component's internal packages. Define boundaries by architectural component, not a blanket prohibition on communication between every nested package.
- Enforce a per-component mapping between external library namespaces and the owning `adapters` subpackage. Check dependencies, constructor calls, method calls, fields, signatures and type inheritance, not only source imports.
- Check that adapter implementation types and external types do not escape through public facades or APIs. Do not assume the Kotlin `internal` modifier alone enforces JVM module isolation.
- Reject concrete provider construction or hard-coded provider references in core. Allow explicit registration and component composition only at their designated boundaries.
- Cover architecture rules with violating examples so their effectiveness is demonstrated. Do not hide failures with blanket ignores, disabled rules or empty imports; document any narrowly scoped, approved exception.
- ArchUnit validates static bytecode relationships, not all reflection, runtime configuration or dynamic behavior. Keep integration tests for actual provider discovery, registration and replacement behind facade contracts.
- Test `ServiceLoader` discovery with independently packaged providers and explicit registration, including missing providers, ambiguous matches, unavailable converters and usable capabilities that remain available when conversion is disabled.
- Test every read/write combination a provider declares. Adding a format to either list adds obligations for all combinations with the other list.
- Test contract metadata validation before provider instantiation, same-major/minimum-minor matching, patch-level interoperability and rejection of incompatible versions without running provider initialization. Include acceptance of a 1.2 provider under 1.9, rejection under 1.1 or 2.x, and numeric minor comparison such as accepting 1.9 under 1.10. Check public contract source and JVM binary compatibility across both patch and minor releases within the same major family.

## REST API and Docker

- Ktor is the approved HTTP framework for the thin server (2026-10-12). Keep Ktor and coroutine APIs inside `dev.nuaforma.server.adapters.ktor`, and Kotlinx JSON serialization inside `dev.nuaforma.server.adapters.json`; neither may leak into public server facades, core or providers. Use the CIO engine, bounded multipart uploads and the existing public engine API. OpenAPI JSON and YAML will use one authoritative contract, not separately authored representations.
- Keep HTTP, authentication, storage identifiers and deployment configuration outside core. Map engine diagnostics to a documented API error contract without exposing internal stack traces.
- Generate or validate downloadable OpenAPI JSON and YAML from one authoritative contract. Avoid manually maintained copies that can drift apart.
- Every generation option exposed by REST must map to an engine capability. Transport-only options must be clearly separate; engine users must not need to fabricate HTTP requests.
- Use bounded upload/download handling and document content types, filenames, status codes and output limits. Never trust an uploaded filename as a filesystem path.
- Hosted and on-premise use must share the same generation semantics. No mandatory cloud account, external telemetry or hidden internet requirement for local generation.
- Containers should run as non-root where feasible, isolate writable temporary storage, expose configuration explicitly and shut down with proper cancellation and cleanup.
- Pin build/runtime dependencies reproducibly. Verify the documented container build and a representative end-to-end request before claiming Docker support.

## Build and distribution boundaries

Organize the build around the product modules and share only necessary configuration. Use `api` and `implementation` dependency scopes deliberately to preserve library boundaries. Publish engine libraries as standard JARs with Maven-compatible publication metadata so consumers can use either Maven or Gradle; our build-tool choice must not constrain theirs.
