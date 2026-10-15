# NuaForma Playground

A separate, development-only Java 25 application over the same REST server and engine. Production `nuaforma-server` artifacts and the `service` image do not depend on this module or contain its browser assets. The five embedded libraries retain Java 17 support. There is no separate generation implementation and no browser framework/CDN dependency.

## Local container preview

Build the server/validator distributions and matching license materials as described in the [service guide](../deployment/pdf-runtime/SERVICE.md), then prepare this application:

```sh
./gradlew :nuaforma-playground:installDist
python3 tools/container/prepare_application_materials.py --application nuaforma-playground/build/install/nuaforma-playground
docker compose -f deployment/pdf-runtime/compose.yaml -f deployment/pdf-runtime/compose.playground.yaml build
docker compose -f deployment/pdf-runtime/compose.yaml -f deployment/pdf-runtime/compose.playground.yaml up -d
```

Open `http://127.0.0.1:8080/playground/`. Compose's sample loopback publication is an example, not an application restriction. Operators can configure ingress or other host bindings; the application does not enforce local-only access. Do not weaken the existing process isolation, seccomp, user or resource settings. No public Playground image is implied by a local build.

The Playground image defaults to `NUAFORMA_PLAYGROUND=true`, `NUAFORMA_MODE=development` and the Playground application entry point. Unknown/empty explicit switches fail startup; enabling the Playground in production mode, or without its module, fails. Setting the switch to `false` disables all UI routes/assets. Production defaults remain unchanged.

## Prepare and generate

The interface loads the running `/openapi.json` and `/api/v1/capabilities`: available provider pairs, provider input/output/general options, profiles, fonts and separate value/content pipe catalogs. Structured provider options use JSON editors, scalar options use typed controls, and unknown extension formats remain selectable when advertised. Availability is not a reservation; **Check requirements** assesses the complete envelope without uploading the template. Only **Generate document** uploads the chosen file/resources and downloads a successful result from the existing multipart endpoint.

Data supports nested JSON objects, arrays and nulls. JSON editor literals, including large integers and decimals, are preserved in the exported/submitted envelope rather than passed through JavaScript number reserialization. The service remains the authoritative validator. Template markers, image content pipes and other features run in the provider, not in the browser.

Provider selection defaults to **Automatically choose a provider**. The JSON then omits `providerId`; the server retains authority over selection and validation. Available formats are obtained from provider capabilities, never inferred from file extensions. A unique format-pair candidate supplies its option/catalog metadata; multiple candidates expose only identical shared option definitions and shared font entries. The interface asks for an explicit provider before generating an ambiguous pair rather than choosing the first registration. Assessment can still explain ambiguity through the API; provider options may affect the server's final assessment.

**Fonts for this document** lists the selected provider's available family/style entries. Bundled faces are informational: already available for rendering, they require no upload or invented resource declaration. Mounted entries with an opaque resource ID can be selected for the existing `{family, face, resource}` output declarations without uploading their bytes. Uploads collect the actual family/style, a logical resource ID and a standalone `.ttf`/`.otf` file; the UI adds the declarations and `resource.<id>` multipart parts together, including curl export placeholders. The provider validates font bytes/metadata and embedding permissions; the browser does not guess a font family from its filename. Choose explicit embed/validate handling for supplied faces; the dedicated controls own the corresponding advanced options until those supplied faces are removed. Duplicate family/style/resource IDs and stale mounted selections are rejected. Selecting fonts does not replace the font families defined by the Word template, install fonts globally or persist uploads across reloads.

Download the JSON envelope and curl instructions separately. The template and logical `resource.<id>` file parts are never embedded in the JSON. Resource files need explicit logical IDs; match them in provider options. Exported curl instructions use replaceable local-file placeholders instead of injecting uploaded filenames into shell code. The exported API origin is this instance; adapt it for the target environment. No authentication credentials are generated or persisted.

## Development and privacy warnings

`/playground`, `/playground/` and its static assets are UI routes, deliberately excluded from both OpenAPI serializations even while enabled. Only the actual REST/health/download operations belong in that contract.

Startup displays an asterisk-framed Playground warning and the existing sensitive-logging warning. The interface always shows a diagonal **DEVELOPMENT MODE** ribbon and readable explanation in light/dark themes. This is not an authentication or authorization mechanism: operators remain responsible for access, sensitive data selection, logs and retention.

No default browser storage, service worker, analytics, external script, remote font or internal-log endpoint is used. Form values and selected files live only in the current page's memory until submitted/reloaded; exported files and explicitly generated downloads are user actions. The server's bounded development logs may contain business values. Existing secret exclusions, API error safety and renderer/validator isolation remain unchanged. Do not use production data.

New functionality requires an explicit Playground impact assessment in issue planning and PR delivery, even when capabilities are discovered dynamically. Update controls, exports, warnings and tests with their owning feature; do not claim unsupported UI coverage.

## Browser request-builder regression checks

Run `node --test nuaforma-playground/src/test/javascript/builder.test.cjs` from the repository root with a Node runtime supporting its built-in test runner. These dependency-free checks exercise the actual request-builder source: automatic/explicit/ambiguous selection, shared metadata, exact JSON literals, font declarations/resources, bundled/mounted distinctions and invalid/duplicate/stale selections. Browser file chooser, multipart submission and real document/font processing still require integration checks against the local application; these checks do not establish visual or barcode fidelity.
