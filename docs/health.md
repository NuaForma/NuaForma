# Service health (source implementation)

`GET /health/live` returns `200 {"status":"UP"}` while the HTTP application is live, independently of converters and generation slots. `GET /health/ready` returns the same success body only after initialization with at least one executable provider pair; otherwise it returns `503 {"status":"DOWN"}`. Both include `X-Request-ID`, `Cache-Control: no-store` and `nosniff`, without diagnostic/provider/configuration details in either mode. They are health states, not the ordinary API error-body schema.

One daemon background observer checks available providers immediately after application startup and every 30 seconds, using a five-second cooperative context budget. A known usable pair short-circuits the remaining observations. Results older than 60 seconds fail readiness. Uncooperative trusted provider code cannot safely be forcibly terminated; the single worker prevents accumulating probe threads and stale observations fail closed. Shutdown marks readiness false before stopping HTTP and cancels the observer. Readiness is not a reservation or a guarantee that a particular template/PDF profile can be processed; inspect capabilities and assess full requirements separately.

Health requests only read lifecycle/cache state. They never take a generation slot, enumerate fonts, start a converter/validator or generate a document. Routine health access logs are suppressed; lifecycle changes and observer failures remain diagnosable. A stopped process cannot return an HTTP status.

## Docker and Compose

The source service image has a `HEALTHCHECK` against readiness using the included Java probe, not an assumed curl/wget installation. `/opt/nuaforma/bin/healthcheck` invokes only the bundled server JVM and a short-timeout loopback HTTP check. It performs no discovery or conversion and prints no response body. Defaults are a 30-second interval, five-second timeout, 60-second startup grace and three retries. Compose permits overriding the timings; Docker CLI also supports its normal health overrides. Container health status alone does not automatically restart the container.

```sh
docker inspect --format '{{json .State.Health}}' <container>
docker exec <container> /opt/nuaforma/bin/healthcheck
```

## Helm, Kubernetes and OpenShift

The maintained source chart is under `deployment/helm/nuaforma`; see its adjacent README. Startup/liveness use `/health/live` and readiness uses `/health/ready`. Startup defaults allow 150 seconds; probe timeouts, periods and thresholds are configurable through values. Kubernetes startup/liveness failure can restart a container; readiness affects service traffic eligibility. Kubernetes does not use the Dockerfile healthcheck as these probes.

Keep probe traffic internal to the workload; no public ingress is required. Preserve non-root execution, reviewed seccomp/device/user-namespace policy, read-only filesystems and all existing limits. OpenShift must satisfy these same prerequisites; chart syntax validation is not a claim that an arbitrary OpenShift cluster is qualified. Operators control ingress/authentication and platform tuning.

Existing public alpha images remain immutable and do not acquire new routes. Their website deployment kits retain compatible defaults; enable health probes only with a matching source/new-release image. The separate Playground shares the server lifecycle and endpoints, not a second readiness model.
