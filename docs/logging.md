# Operational logging and development diagnostics

Available in `0.2.0-alpha.1`, from issue #76. Historical `0.1` images and libraries remain unchanged.

## Server and container configuration

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

API errors remain safe in both modes. Internal exceptions retain bounded class/cause chains and class/method/line stack frames, never exception messages, suppressed messages or raw subprocess output. Known runtime failures have application-owned actionable codes: missing launcher/runtime, mismatched runtime identity, user-namespace denial, device-mount denial, OS permission denial, missing runtime files and process memory limits. Unknown child failures retain exit status and a safe troubleshooting action; development adds capture metadata, not raw child text.

Providers log identity, declared and usable format sets, full/partial availability and safe registration/compatibility/readiness reasons. Identical request-time readiness reports are suppressed for 30 seconds per registered provider; changed states are reported immediately. Requests retain their own request/rejection events. The additive optional `ProviderReadiness` facet receives explicit context; old `DocumentProvider` implementations keep their original methods. Assessment is not a reservation.

## Sensitive development traces and limits

Development records bounded field paths/source offsets and values, plus custom/built-in/content pipe identities, arguments and input/output stages. Chained failures remain traceable without changing API errors. Offsets are local to each parsed text fragment, not page numbers/global Word coordinates. Structured image results are opaque. There is no whole-document/template, HTTP-header/body, configuration or resource dump.

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
