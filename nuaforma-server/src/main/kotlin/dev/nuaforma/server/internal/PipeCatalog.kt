package dev.nuaforma.server.internal

import dev.nuaforma.contracts.*

/** Metadata from the provider's captured evaluation registry, never classpath scanning. */
internal object PipeCatalog {
    fun describe(provider: DocumentProvider, declared: ProviderCapabilities, available: ProviderAvailability,
                 context: GenerationContext): Map<String, Any?> {
        context.checkActive()
        val valueStatus = if (provider is TemplatePipeProvider) "AVAILABLE" else "UNSUPPORTED"
        val contentStatus = if (provider is ContentPipeProvider) "AVAILABLE" else "UNSUPPORTED"
        return try {
            val values = (provider as? TemplatePipeProvider)?.templatePipes().orEmpty().toList()
            val contents = (provider as? ContentPipeProvider)?.contentPipes().orEmpty().toList()
            require(values.size <= 128 && contents.size <= 128)
            val names = values.map { it.name } + contents.map { it.name }
            require(names.distinct().size == names.size)
            contents.forEach { definition -> require(definition.support.all { declared.supports(it.input, it.output) }) }
            context.checkActive()
            mapOf(
                "valuePipes" to catalog(valueStatus, values.sortedBy { it.name }.map { definition ->
                    mapOf("name" to definition.name, "description" to definition.description, "chainable" to true,
                        "arguments" to arguments(definition.arguments), "nullBehavior" to definition.nullBehavior,
                        "available" to (available.readFormats.isNotEmpty() && available.writeFormats.isNotEmpty()))
                }),
                "contentPipes" to catalog(contentStatus, contents.sortedBy { it.name }.map { definition ->
                    val support = definition.support.sortedWith(compareBy({ it.input.id }, { it.output.id }))
                    mapOf("name" to definition.name, "description" to definition.description, "chainable" to false,
                        "arguments" to arguments(definition.arguments), "nullBehavior" to definition.nullBehavior,
                        "contexts" to definition.contexts.map { it.name.lowercase() }.sorted(),
                        "contentTypes" to definition.contentTypes.sorted(),
                        "declaredSupport" to support.map(::combination),
                        "availableSupport" to support.filter { available.supports(it.input, it.output) }.map(::combination))
                }))
        } catch (error: Exception) {
            context.checkActive()
            context.logging.failure("PIPE_CATALOG_UNAVAILABLE", error)
            unavailable(valueStatus, contentStatus)
        } catch (error: LinkageError) {
            context.logging.failure("PIPE_CATALOG_UNAVAILABLE", error)
            unavailable(valueStatus, contentStatus)
        } catch (error: java.util.ServiceConfigurationError) {
            context.logging.failure("PIPE_CATALOG_UNAVAILABLE", error)
            unavailable(valueStatus, contentStatus)
        }
    }

    private fun unavailable(valueStatus: String, contentStatus: String) = mapOf(
        "valuePipes" to catalog(if (valueStatus == "UNSUPPORTED") valueStatus else "UNAVAILABLE", emptyList()),
        "contentPipes" to catalog(if (contentStatus == "UNSUPPORTED") contentStatus else "UNAVAILABLE", emptyList()))

    private fun catalog(status: String, entries: List<Map<String, Any?>>): Map<String, Any?> = mapOf(
        "status" to status, "entries" to entries,
        "diagnostics" to if (status == "UNAVAILABLE") listOf(mapOf("code" to "PIPE_METADATA_UNAVAILABLE", "severity" to "ERROR",
            "message" to "Provider pipe metadata is invalid or unavailable")) else emptyList())

    private fun combination(support: ContentPipeSupport): Map<String, Any?> = mapOf(
        "input" to support.input.id, "output" to support.output.id, "profiles" to support.profiles.sorted())

    private fun arguments(values: List<OptionDefinition>): List<Map<String, Any?>> = values.map {
        mapOf("name" to it.name, "type" to it.type.name, "description" to it.description, "required" to it.required,
            "defaultValue" to it.defaultValue, "allowedValues" to it.allowedValues)
    }
}
