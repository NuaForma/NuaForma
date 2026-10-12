package dev.nuaforma.server.internal

import dev.nuaforma.server.adapters.json.KotlinxJsonCodec
import java.nio.file.Files
import java.nio.file.Path

/** Both serializations consume the same JSON-compatible document model. No vendor models enter production. */
internal class ApiDocumentation(private val json: JsonCodec = KotlinxJsonCodec(), model: Map<String, Any> = OpenApiContract.document()) {
    val jsonText = json.encode(model)
    val yamlText = yaml(model, 0) + "\n"

    // A JSON-only YAML 1.2 emitter: quote every string/key; never emit tags, anchors or implicit strings.
    private fun yaml(value: Any?, depth: Int): String {
        val indentation = "  ".repeat(depth)
        return when (value) {
            is Map<*, *> -> if (value.isEmpty()) "{}" else value.entries.joinToString("\n") { (key, child) ->
                indentation + json.encode(key) + ":" + if (container(child)) "\n" + yaml(child, depth + 1) else " " + json.encode(child)
            }
            is List<*> -> if (value.isEmpty()) "[]" else value.joinToString("\n") { child ->
                indentation + "-" + if (container(child)) "\n" + yaml(child, depth + 1) else " " + json.encode(child)
            }
            else -> json.encode(value)
        }
    }

    private fun container(value: Any?) = (value is Map<*, *> && value.isNotEmpty()) || (value is List<*> && value.isNotEmpty())
}

internal object OpenApiExport {
    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size == 1)
        val destination = Path.of(arguments[0])
        Files.createDirectories(destination)
        val document = ApiDocumentation()
        Files.writeString(destination.resolve("nuaforma-openapi.json"), document.jsonText + "\n")
        Files.writeString(destination.resolve("nuaforma-openapi.yaml"), document.yamlText)
    }
}
