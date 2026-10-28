# Operational logging and development diagnostics

## Server and container configuration

The server supports independent strict startup `NUAFORMA_PDFA_VALIDATION`: unset/exact `true` keeps conformance validation enabled, exact `false` delegates it downstream, and every other value rejects startup without echoing the supplied value. Disabled policy always emits an asterisk-framed WARNING, including production: **PDF/A conformance is not checked by NuaForma**; the operator owns downstream validation. Default-on startup emits no such warning. This uses no document/request/configuration dump. Disabled generations emit no per-document veraPDF-start/completion event or attestation; PDFBox and all other isolation/resource checks remain mandatory. The probe still verifies bundled veraPDF readiness. Libraries use explicit captured instance configuration and opt-in logging, not environment reads.

`NUAFORMA_MODE` is trusted startup configuration, read once before provider discovery:

| Value | Behavior |
| --- | --- |
| Unset or `production` | Safe operational events, provider readiness and unexpected technical failure stacks |
| `development` | The same operational logging plus bounded sensitive template/field/pipe diagnostics |
| Empty or anything else | Startup fails without echoing the rejected value |

Set the variable in the server environment or the container's `environment` section. For example, add `NUAFORMA_MODE: development` to your own Compose service when using synthetic template-development data. HTTP headers, uploaded JSON, template markers and generation options cannot select the mode. In-process trusted extensions remain executable operator-owned code, not a sandbox.

Before serving development requests, the server prints:

```text
************************************************************
* DEVELOPMENT MODE — SENSITIVE DATA LOGGING ENABLED         *
* Document content and field values may appear in logs.    *
* DO NOT USE WITH PRODUCTION DATA.                         *
************************************************************
```

The default server logger writes to standard output, with timestamps, levels, stable event codes and a `requestId`. Unexpected failures use ERROR; expected rejection/readiness diagnostics use INFO/WARN; sensitive traces use DEBUG and are emitted only in development. Library code does not configure a backend. The standalone server uses approved Log4j 2 behind SLF4J in its logging adapter. Other logger categories are disabled because third-party messages may contain document contents/secrets. Enabling other categories or replacing operator-owned logging configuration requires a separate security review.

## Correlation and safe failures

Every request reaching the application receives a fresh server-generated UUID in `X-Request-ID`; safe JSON error bodies also contain the matching `requestId`. Valid requests, malformed envelopes, unknown routes, capacity rejection and generation errors are covered. Rejections before the application, for example by a reverse proxy or HTTP engine, cannot receive an application identity. Incoming correlation headers are ignored; external correlation IDs are not currently supported.

Scoped coroutine-aware MDC preserves the ID through thread switches, nesting, blocking engine/provider calls and cancellation. Explicit NuaForma context carries the same identity through libraries and logs around sandbox processes. No MDC/request data is added to child environment, arguments or mounts. Startup events use `requestId=-`, not an invented identity.

The API keeps production errors restricted and gives development generation/assessment errors fixed application-owned explanations and remediation. Optional `reason`, `remediation`, `part`, `construct` and `location` fields accompany existing codes/severity/messages only for recognized reasons. `RejectionReason` is a closed catalog; free-text diagnostics from old providers/extensions remain generic, even when they reuse a known code or message. No arbitrary exception or extension text is forwarded. Installed executable extensions can select catalog entries but cannot introduce new catalog text. This is a disclosure boundary, not a sandbox for trusted extension code.

Both modes log up to 32 rejection reasons per request as correlated `GENERATION_REJECTED` events, including safe explanation/remediation and available coordinates, subject to the operational scope budget below. API arrays retain at most 64 diagnostics. Catalog messages/advice are at most 256 characters; reason IDs at most 128, construct labels at most 64. Only known logical DOCX part names are exposed (at most 128 characters); arbitrary package names become `[other package part]`. Locations allow only `template:<offset>` or `xml:<line>:<column>`, each number at most eight digits. Template offsets are fragment-local and zero-based; XML coordinates are parser positions (zero when unknown), not Word page positions. These constrained labels contain no arbitrary values or controls; JSON encoding and bounded log escaping apply independently. XML/package parsing captures logical part/coordinates before replacing parser errors, without retaining parser messages, external targets or document snippets.

Internal exceptions retain bounded class/cause chains and class/method/line stack frames, never exception messages, suppressed messages or raw subprocess output. Known runtime failures have application-owned actionable codes: missing launcher/runtime, mismatched runtime identity, user-namespace denial, device-mount denial, OS permission denial, missing runtime files and process memory limits. Unknown child failures retain exit status and a safe troubleshooting action; development adds capture metadata, not raw child text.

Providers log identity, declared and usable format sets, full/partial availability and safe registration/compatibility/readiness reasons. Identical request-time readiness reports are suppressed for 30 seconds per registered provider; changed states are reported immediately. Requests retain their own request/rejection events. The additive optional `ProviderReadiness` facet receives explicit context; old `DocumentProvider` implementations keep their original methods. Assessment is not a reservation.

## Sensitive development traces and limits

### Template syntax context (source after 0.3.0-alpha.2)

Development generation failures may include `expression` (at most 256 characters), `expressionPosition` (zero-based UTF-16 position in that excerpt), `expressionStart` (excerpt offset in its expression/marker), `expressionSourceOffset` (expression/marker offset in its local fragment), and `documentLocation` such as `paragraph:4`. Numeric API coordinates are decimal strings. An error may point just beyond the last character for a missing operand/delimiter. Control characters are replaced with spaces, so the Playground can place a caret without rendering HTML. Known secret field expressions are replaced with `[redacted expression]`; other template literals may contain business information.

The DOCX adapter supplies the logical `part` and a one-based paragraph index when available, counting paragraphs in tables/text boxes as well as body text. This is a logical location, not a page or Word line number. Structural markers and generic parsing failures may have less context. Long expressions show an excerpt around the error rather than the full text. These fields are absent from production responses and no excerpts are recorded in production logs.

Both modes still emit `GENERATION_REJECTED` with fixed reasons. Development additionally emits correlated `TEMPLATE_SYNTAX_DETAIL` at DEBUG through the existing bounded sensitive trace budget. The shipped console logger enables this category at DEBUG. Curl receives the same development JSON as the Playground; use `--fail-with-body` instead of `--fail` to retain error bodies. The Playground displays the excerpt, caret and document location beside the request ID. Enabling development mode is still a trusted startup decision, never a request option. Arbitrary extension/exception messages and stack traces remain excluded from HTTP errors.

The service reports individual rejected mounted fonts once per provider snapshot when an enabled logging context first becomes available (normally startup activation). `MOUNTED_FONT_REJECTED` names the escaped basename and a safe loading-failure result in both modes; identity conflicts also name a conflicting basename. Development adds `MOUNTED_FONT_REJECTION_DETAIL` with a fixed reason and at most four exception/cause types with four class/method/line frames each. Exception messages, source filenames/full paths, suppressed text, font bytes and credentials are excluded. These expected file failures use the existing operational/development budgets, leaving the unexpected-failure reserve intact. Each of the at most 64 font files fits the normal uncorrelated startup event allowance; an explicitly supplied request scope retains its smaller limits. Library logging remains opt-in.

Development records bounded field paths/source offsets and values, plus custom/built-in/content pipe identities, arguments and input/output stages. Chained failures remain traceable; these sensitive traces never become API error text. Offsets are local to each parsed text fragment, not page numbers/global Word coordinates. Structured image results are opaque. There is no whole-document/template, HTTP-header/body, configuration or resource dump.

Known secret field names (authorization/cookie/token/password/passwd/secret/credential/private-key/API-key variants) are redacted recursively; source-path redaction follows a value through a marker's pipe chain and supported collection aliases. Known transport/configuration credentials never enter expression diagnostics. This is not semantic recognition of arbitrary secrets in business strings or trusted-extension output: use synthetic data and assume development logs contain confidential information.

Sensitive diagnostics are capped per scope at 256 events/128 KiB. Operational events have a separate allowance of 64 events/64 KiB per request or 4,096 events/1 MiB per uncorrelated startup scope. Unexpected failures have an independent reserve of 16 events/64 KiB; saturated traces/readiness events cannot consume it. Each event's text is at most 3.5 KiB UTF-8, leaving room for its bounded identity/code metadata within a 4 KiB log message, with bounded nodes/depth/entries, escaped controls and opaque unsupported objects. Long payload-like strings, binary values and full image payloads are omitted. Stacks have at most four causes/twelve frames per cause within the event bound. Renderer stderr capture retains at most 4 KiB and drains/discards the rest; only allowlisted classifications leave the capture. Sink errors do not replace generation outcomes. Custom sinks/extensions remain responsible for their own allocations and cancellation cooperation.

## Embedded configuration

Libraries require no SLF4J/MDC, environment variables or global logger. Supply an explicit request-private scope and map sanitized events to your backend:

```kotlin
val logging = GenerationLogging(LoggingMode.DEVELOPMENT, "synthetic-example") { event ->
    println("${event.level} ${event.requestId} ${event.code} ${event.text}")
}
val context = GenerationContext(logging)
registry.generate(request, output, context, "nuaforma-docx")
```

Java uses the same constructors and `GenerationLogSink` functional interface. Original `GenerationContext` constructors/default JVM descriptors and `DocumentProvider` methods remain unchanged. The additive five-argument context constructor combines limits/cancellation/deadline/clock/logging. Additive `ProviderRegistry.discover`/`registered` overloads accept logging for discovery; create a fresh scope for every generation. Default library logging is a true no-op, not a shared diagnostic budget.

## Collection and retention

Collect stdout/stderr with your platform or agent, optionally forwarding to an organization-managed logging service. There is no remote destination, mandatory SaaS, upload or telemetry. Operators own access controls, forwarding security, retention/deletion and incident handling. Never attach real development documents/values/secrets to public issues. Restore production mode and restart after debugging.
