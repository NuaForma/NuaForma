package dev.nuaforma.server.internal

internal object ApiRoutes {
    const val VERSION = "/api/v1/version"
    const val CAPABILITIES = "/api/v1/capabilities"
    const val ASSESS = "/api/v1/assess"
    const val DOCUMENTS = "/api/v1/documents"
    const val OPENAPI_JSON = "/openapi.json"
    const val OPENAPI_YAML = "/openapi.yaml"
    val operations = mapOf(VERSION to "get", CAPABILITIES to "get", ASSESS to "post", DOCUMENTS to "post", OPENAPI_JSON to "get", OPENAPI_YAML to "get")
}
