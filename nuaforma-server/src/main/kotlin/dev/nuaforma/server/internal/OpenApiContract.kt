package dev.nuaforma.server.internal

import dev.nuaforma.server.ServerConfiguration

/** Authoritative development HTTP contract, independent of installed providers and their runtime readiness. */
internal object OpenApiContract {
    private fun ref(name: String) = mapOf("\$ref" to "#/components/schemas/$name")
    private fun string() = mapOf("type" to "string")
    private fun array(items: Any) = mapOf("type" to "array", "items" to items)
    private fun obj(properties: Map<String, Any>, required: List<String> = properties.keys.toList()) =
        mapOf("type" to "object", "properties" to properties, "required" to required, "additionalProperties" to false)
    private fun jsonObject() = mapOf("type" to "object", "additionalProperties" to true)
    private fun content(schema: Any, media: String = "application/json") = mapOf(media to mapOf("schema" to schema))
    private fun response(description: String, schema: Any) = mapOf("description" to description, "content" to content(schema))

    private fun errors() = linkedMapOf<String, Any>(
        "400" to response("Invalid JSON/envelope/multipart fields or filename", ref("ApiError")),
        "408" to response("Cancellation or request/generation deadline", ref("ApiError")),
        "413" to response("Upload, output or processing resource budget exceeded", ref("ApiError")),
        "415" to response("Unsupported request/part media type or compressed request body", ref("ApiError")),
        "422" to response("Unsupported requirements, ambiguous provider, invalid template/data/options", ref("ApiError")),
        "500" to response("Internal/provider/IO failure without partial success output", ref("ApiError")),
        "503" to (response("Unavailable provider/backend or HTTP capacity exhausted", ref("ApiError")) + mapOf("headers" to mapOf(
            "Retry-After" to mapOf("description" to "Present with value 1 only when capacity is exhausted", "schema" to string()),
        ))),
    )

    fun document(): Map<String, Any> {
        val defaults = ServerConfiguration()
        val example = mapOf("input" to mapOf("format" to "docx"), "output" to mapOf("format" to "docx"),
            "providerId" to "nuaforma-docx", "filename" to "agreement", "data" to mapOf("customer" to mapOf("name" to "Ada"), "reference" to "NF001"),
            "options" to mapOf("locale" to "nl-NL", "timeZone" to "Europe/Amsterdam",
                "watermark" to mapOf("text" to "CONCEPT — {customer.name}", "color" to "#B0B0B0", "opacity" to 0.25, "rotation" to -45)))
        val schemas = linkedMapOf<String, Any>(
            "Health" to obj(mapOf("status" to (string() + mapOf("enum" to listOf("UP", "DOWN"))))),
            "BuildVersion" to obj(mapOf(
                "version" to (string() + mapOf("description" to "Application release/development version recorded during the build.")),
                "contractVersion" to (string() + mapOf("description" to "Runtime contract major.minor, independent of the full release version.")),
                "commit" to mapOf("anyOf" to listOf(string() + mapOf("pattern" to "^(?:[0-9a-f]{40}|[0-9a-f]{64})$"), mapOf("type" to "null")),
                    "description" to "Full source commit recorded during the build; null when Git identity is unavailable, such as a source archive. Not a proof of clean sources or release approval."),
            )),
            "FormatIdentifier" to (string() + mapOf("pattern" to "^[a-z][a-z0-9.+-]{0,127}$", "description" to "Extensible identifier, not an enum. Installed ready providers determine usable format pairs.")),
            "FormatRequest" to obj(mapOf("format" to ref("FormatIdentifier"), "options" to (jsonObject() + mapOf("description" to "Provider-interpreted options. Unknown explicit options fail. Consult capability metadata and assess complete combinations."))), listOf("format")),
            "TextWatermark" to obj(mapOf(
                "text" to (string() + mapOf("minLength" to 1, "maxLength" to 4096, "x-max-utf16-code-units" to 4096, "pattern" to "\\S", "description" to "Nonblank source, at most 4096 UTF-16 code units (astral characters count twice). Same restricted language/root data and cumulative budgets as the document. Resolve once; inserted values are literal. Blank resolved text adds no watermark.")),
                "color" to (string() + mapOf("pattern" to "^#[0-9a-fA-F]{6}$", "default" to "#B0B0B0")),
                "opacity" to mapOf("type" to "number", "minimum" to 0, "maximum" to 1, "default" to 0.25),
                "rotation" to mapOf("type" to "number", "minimum" to -360, "maximum" to 360, "default" to -45),
            ), listOf("text")),
            "GenerationOptions" to obj(mapOf(
                "watermark" to mapOf("anyOf" to listOf(ref("TextWatermark"), mapOf("type" to "null"))),
                "locale" to (string() + mapOf("description" to "Java Locale language tag; defaults to root. Empty tag also represents root.")),
                "timeZone" to (string() + mapOf("default" to "UTC", "description" to "Valid Java ZoneId; independent of the server's local zone.")),
                "extensions" to (jsonObject() + mapOf("description" to "Provider-interpreted general options, never HTTP or converter configuration.")),
            ), emptyList()),
            "GenerationEnvelope" to (obj(mapOf(
                "input" to ref("FormatRequest"), "output" to ref("FormatRequest"), "data" to (jsonObject() + mapOf("default" to emptyMap<String, Any>(), "description" to "Arbitrary nested JSON objects/arrays/scalars/nulls; every supplied field is accessible. Only caller-supplied data reaches expressions, not HTTP/authentication/host state.")),
                "options" to ref("GenerationOptions"),
                "providerId" to (string() + mapOf("pattern" to "^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$", "description" to "Optional explicit registered provider. Required when matches are ambiguous; never a class name or installation request.")),
                "filename" to (string() + mapOf("pattern" to "^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$", "default" to "document", "description" to "Transport-only ASCII basename; actual output format supplies the extension. Uploaded filenames are ignored.")),
            ), listOf("input", "output")) + mapOf("example" to example)),
            "Diagnostic" to obj(mapOf("code" to (string() + mapOf("pattern" to "^[A-Z][A-Z0-9_.-]{0,127}$")),
                "severity" to (string() + mapOf("enum" to listOf("WARNING", "ERROR"))), "message" to (string() + mapOf("description" to "Safe message, not raw provider input, paths, stack traces or generated content.")))),
            "ApiError" to obj(mapOf("requestId" to (string() + mapOf("format" to "uuid", "description" to "Server-generated identity, also returned in X-Request-ID")),
                "diagnostics" to (array(ref("Diagnostic")) + mapOf("maxItems" to 64)))),
            "Assessment" to obj(mapOf("status" to (string() + mapOf("enum" to listOf("SUPPORTED"))), "providerId" to string())),
            "FormatSets" to obj(mapOf("readFormats" to array(ref("FormatIdentifier")), "writeFormats" to array(ref("FormatIdentifier")))),
            "OptionDefinition" to obj(mapOf("name" to string(), "type" to (string() + mapOf("enum" to listOf("STRING", "INTEGER", "NUMBER", "BOOLEAN", "OBJECT", "ARRAY"))),
                "description" to string(), "required" to mapOf("type" to "boolean"), "defaultValue" to emptyMap<String, Any>(), "allowedValues" to array(emptyMap<String, Any>()))),
            "AvailableFont" to obj(mapOf("family" to string(), "face" to string(), "source" to (string() + mapOf("enum" to listOf("bundled", "mounted"))),
                "resource" to mapOf("anyOf" to listOf(string(), mapOf("type" to "null"))))),
            "FontCatalog" to obj(mapOf("status" to (string() + mapOf("enum" to listOf("AVAILABLE", "UNSUPPORTED", "UNAVAILABLE"))),
                "entries" to (array(ref("AvailableFont")) + mapOf("maxItems" to 2048)))),
            "PipeSupport" to obj(mapOf("input" to ref("FormatIdentifier"), "output" to ref("FormatIdentifier"),
                "profiles" to (array(string()) + mapOf("description" to "Empty means all profiles qualified by this provider, not arbitrary PDF profiles.")))),
            "ValuePipe" to obj(mapOf("name" to string(), "description" to string(), "chainable" to mapOf("type" to "boolean", "enum" to listOf(true)),
                "arguments" to array(ref("OptionDefinition")), "nullBehavior" to string(), "available" to mapOf("type" to "boolean"))),
            "ContentPipe" to obj(mapOf("name" to string(), "description" to string(), "chainable" to mapOf("type" to "boolean", "enum" to listOf(false)),
                "arguments" to array(ref("OptionDefinition")), "nullBehavior" to string(), "contexts" to array(string()), "contentTypes" to array(string()),
                "declaredSupport" to array(ref("PipeSupport")), "availableSupport" to array(ref("PipeSupport")))),
            "ValuePipeCatalog" to obj(mapOf("status" to (string() + mapOf("enum" to listOf("AVAILABLE", "UNSUPPORTED", "UNAVAILABLE"))),
                "entries" to (array(ref("ValuePipe")) + mapOf("maxItems" to 128)), "diagnostics" to array(ref("Diagnostic")))),
            "ContentPipeCatalog" to obj(mapOf("status" to (string() + mapOf("enum" to listOf("AVAILABLE", "UNSUPPORTED", "UNAVAILABLE"))),
                "entries" to (array(ref("ContentPipe")) + mapOf("maxItems" to 128)), "diagnostics" to array(ref("Diagnostic")))),
            "Provider" to obj(mapOf("id" to string(), "releaseVersion" to string(), "requiredContract" to string(), "fonts" to ref("FontCatalog"),
                "valuePipes" to ref("ValuePipeCatalog"), "contentPipes" to ref("ContentPipeCatalog"),
                "declared" to ref("FormatSets"), "available" to ref("FormatSets"),
                "inputOptions" to mapOf("type" to "object", "additionalProperties" to array(ref("OptionDefinition"))),
                "outputOptions" to mapOf("type" to "object", "additionalProperties" to array(ref("OptionDefinition"))),
                "generalOptions" to array(ref("OptionDefinition")), "diagnostics" to (array(ref("Diagnostic")) + mapOf("maxItems" to 64)))),
            "Capabilities" to obj(mapOf("contractVersion" to string(), "providers" to array(ref("Provider")), "diagnostics" to (array(ref("Diagnostic")) + mapOf("maxItems" to 64)))),
        )
        val download = mapOf("description" to "Completed validated output. No document is published after a generation failure; a later network failure may truncate a committed attachment.",
            "content" to (content(mapOf("type" to "string", "format" to "binary"), "application/vnd.openxmlformats-officedocument.wordprocessingml.document") +
                content(mapOf("type" to "string", "format" to "binary"), "application/pdf") + content(mapOf("type" to "string", "format" to "binary"), "application/octet-stream")),
            "headers" to mapOf("Content-Disposition" to mapOf("schema" to string(), "description" to "attachment; filename=\"<basename>.<actual-format>\""),
                "Cache-Control" to mapOf("schema" to string(), "description" to "no-store"),
                "X-Content-Type-Options" to mapOf("schema" to string(), "description" to "nosniff"),
                "NuaForma-Warning-Codes" to mapOf("schema" to string(), "description" to "Comma-separated diagnostic codes; may be empty. At most 32 distinct codes.")))
        val multipart = mapOf("type" to "object", "required" to listOf("request", "template"),
            "properties" to mapOf("request" to ref("GenerationEnvelope"), "template" to mapOf("type" to "string", "format" to "binary")),
            "patternProperties" to mapOf("^resource\\.[A-Za-z0-9][A-Za-z0-9._-]{0,127}$" to mapOf("type" to "string", "format" to "binary")),
            "additionalProperties" to false,
            "description" to "Exactly one request (application/json) and template file part, plus optional resource.<id> file parts in any order. Duplicates/unknown names fail. Resource suffixes are the logical font/resource IDs in provider options, never paths/URLs. For DOCX, template media type must be its document type or application/octet-stream. Some OpenAPI clients do not support patternProperties multipart fields; add named resource file parts explicitly.")
        val paths = linkedMapOf<String, Any>(
            ApiRoutes.SBOM to mapOf("get" to mapOf("operationId" to "downloadSbom", "summary" to "Download the static shipped-component CycloneDX 1.7 inventory",
                "description" to "Build-generated snapshot, not a runtime scan, vulnerability assessment or license audit. Standalone scope is JVM production dependencies; container scope includes bundled native/JVM/OS components. Operator extensions, private fonts and request data are excluded. Read metadata properties for scope and source identity.",
                "responses" to (errors() + mapOf("200" to mapOf("description" to "Build-validated CycloneDX 1.7 JSON attachment", "content" to content(jsonObject(), "application/vnd.cyclonedx+json"),
                    "headers" to mapOf("Content-Disposition" to mapOf("schema" to string()), "ETag" to mapOf("schema" to string()), "Cache-Control" to mapOf("schema" to string()))))))),
            ApiRoutes.LIVE to mapOf("get" to mapOf("operationId" to "getLiveness", "summary" to "Check HTTP application liveness",
                "description" to "Independent of converters and generation slots; no document or provider processing. No internal details in any mode.",
                "responses" to mapOf("200" to response("Application is live", ref("Health")), "503" to response("Application is not live", ref("Health"))))),
            ApiRoutes.READY to mapOf("get" to mapOf("operationId" to "getReadiness", "summary" to "Check cached service readiness",
                "description" to "Initialization complete, accepting work and at least one executable pair. Background observations every 30 seconds expire after 60 seconds; no probe runs in this request. Not a template or PDF-specific guarantee.",
                "responses" to mapOf("200" to response("Service accepts generation work", ref("Health")), "503" to response("Starting, draining, stale or no usable providers", ref("Health"))))),
            ApiRoutes.VERSION to mapOf("get" to mapOf("operationId" to "getVersion", "summary" to "Identify the running application build",
                "description" to "Packaged build metadata and runtime contract version. Does not query provider readiness, fonts or converters; uses normal HTTP admission/deadline limits.",
                "responses" to (errors() + mapOf("200" to (response("Build identity; not a readiness or release-approval signal", ref("BuildVersion")) + mapOf("headers" to mapOf(
                    "Cache-Control" to mapOf("schema" to string(), "description" to "no-store"),
                    "X-Content-Type-Options" to mapOf("schema" to string(), "description" to "nosniff"),
                ))))))),
            ApiRoutes.CAPABILITIES to mapOf("get" to mapOf("operationId" to "getCapabilities", "summary" to "Inspect declared and usable provider capabilities", "description" to "Availability is reassessed, not a reservation. Each usable read/write pair belongs to one provider. Format-specific versions/profiles/options remain dynamic provider metadata.",
                "responses" to (errors() + mapOf("200" to response("Provider catalog and safe availability/discovery diagnostics", ref("Capabilities")))))),
            ApiRoutes.ASSESS to mapOf("post" to mapOf("operationId" to "assessGeneration", "summary" to "Assess complete requirements without a template", "description" to "Does not validate template/font content or reserve availability. The same envelope is accepted; data and filename do not affect selection.",
                "requestBody" to mapOf("required" to true, "content" to content(ref("GenerationEnvelope"))), "responses" to (errors() + mapOf("200" to response("One compatible ready provider matches the complete request", ref("Assessment")))))),
            ApiRoutes.DOCUMENTS to mapOf("post" to mapOf("operationId" to "generateDocument", "summary" to "Generate with template, structured data, options and optional fonts", "description" to "Same engine semantics as embedded use. Initial built-in outputs: DOCX and static standard PDF 1.7 with the qualified isolated backend. Not PDF/A, PDF/UA or forms. DOCX remains unchanged; scoped PDF text-box limitation #32 applies. Fonts follow technical embedding permissions without proof-of-ownership requirements.",
                "requestBody" to mapOf("required" to true, "content" to mapOf("multipart/form-data" to mapOf("schema" to multipart, "encoding" to mapOf("request" to mapOf("contentType" to "application/json"))))),
                "responses" to (errors() + mapOf("200" to download)))),
        )
        listOf(ApiRoutes.OPENAPI_JSON to "application/json", ApiRoutes.OPENAPI_YAML to "application/yaml").forEach { (path, media) ->
            val schema = if (path == ApiRoutes.OPENAPI_JSON) jsonObject() else string()
            paths[path] = mapOf("get" to mapOf("operationId" to if (path == ApiRoutes.OPENAPI_JSON) "downloadOpenApiJson" else "downloadOpenApiYaml", "summary" to "Download this authoritative API contract", "responses" to (errors() + mapOf("200" to mapOf("description" to "Equivalent OpenAPI 3.1.1 document serialized as $media", "content" to content(schema, media),
                "headers" to mapOf("Content-Disposition" to mapOf("schema" to string(), "description" to "OpenAPI attachment filename")))))))
        }
        val correlatedPaths = paths.mapValues { (_, path) ->
            @Suppress("UNCHECKED_CAST")
            (path as Map<String, Map<String, Any>>).mapValues { (_, operation) ->
                @Suppress("UNCHECKED_CAST")
                operation + mapOf("responses" to (operation.getValue("responses") as Map<String, Map<String, Any>>).mapValues { (_, response) ->
                    @Suppress("UNCHECKED_CAST")
                    response + mapOf("headers" to ((response["headers"] as? Map<String, Any>).orEmpty() + mapOf("X-Request-ID" to mapOf(
                        "schema" to (string() + mapOf("format" to "uuid")), "description" to "Server-generated request identity; incoming headers cannot replace it"))))
                })
            }
        }
        return linkedMapOf("openapi" to "3.1.1", "info" to mapOf("title" to "NuaForma REST API", "version" to BuildInformation.version, "license" to mapOf("name" to "Apache License 2.0", "identifier" to "Apache-2.0"),
            "description" to "Development-stage API, not a stable release or public deployment. Loopback by default; no built-in authentication/TLS. Use an authenticated TLS gateway and deployment/connection/header quotas. No hidden network fetching or telemetry. Installed extensions are trusted executable application code."),
            "servers" to listOf(mapOf("url" to "/", "description" to "Relative to the deployment; no host environment is reflected")), "security" to emptyList<Any>(), "paths" to correlatedPaths, "components" to mapOf("schemas" to schemas),
            "x-default-limits" to mapOf("maximumConcurrentRequests" to defaults.maximumConcurrentRequests, "maximumRequestBytes" to defaults.maximumRequestBytes, "maximumJsonBytes" to defaults.maximumJsonBytes,
                "maximumTemplateBytes" to defaults.generationLimits.maxTemplateBytes, "maximumResourceBytes" to defaults.maximumResourceBytes, "maximumResources" to defaults.maximumResources,
                "maximumOutputBytes" to defaults.generationLimits.maxOutputBytes, "requestTimeoutSeconds" to defaults.requestTimeout.seconds, "generationTimeoutSeconds" to defaults.generationLimits.maxDuration.seconds,
                "description" to "Trusted server configuration may specialize these defaults; request-selected increases are not supported. Strict UTF-8/duplicate keys, JSON depth/token/number limits, archive/expression/repetition budgets and hard deployment quotas also apply."))
    }
}
