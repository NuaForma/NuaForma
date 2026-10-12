package dev.nuaforma.server

import com.fasterxml.jackson.databind.JsonNode
import java.math.BigDecimal

/** Test-only assertions for the schema keywords used by this contract; not a general-purpose validator. */
internal class OpenApiSchemaChecks(private val document: JsonNode) {
    fun matches(name: String, value: JsonNode): Boolean = matches(document.path("components").path("schemas").path(name), value)

    fun matches(schema: JsonNode, value: JsonNode): Boolean {
        if (schema.isBoolean) return schema.booleanValue()
        check(schema.isObject) { "Missing/non-object schema" }
        val supported = setOf("\$ref", "type", "properties", "required", "additionalProperties", "patternProperties", "items", "anyOf", "enum", "pattern", "minLength", "maxLength", "maxItems", "minimum", "maximum", "description", "default", "example", "format", "x-max-utf16-code-units")
        check(schema.fieldNames().asSequence().all { it in supported }) { "Add assertion support before adding schema keywords" }
        if (schema.has("\$ref")) {
            val reference = schema.path("\$ref").asText()
            check(reference.startsWith("#/components/schemas/")) { "Only local schema references are permitted" }
            return matches(document.at(reference.removePrefix("#")), value)
        }
        if (schema.has("anyOf") && schema.path("anyOf").none { matches(it, value) }) return false
        if (schema.has("type")) {
            val accepted = when (schema.path("type").asText()) {
                "object" -> value.isObject
                "array" -> value.isArray
                "string" -> value.isTextual
                "number" -> value.isNumber
                "integer" -> value.isNumber && value.decimalValue().stripTrailingZeros().scale() <= 0
                "boolean" -> value.isBoolean
                "null" -> value.isNull
                else -> error("Unsupported schema type")
            }
            if (!accepted) return false
        }
        if (schema.has("enum") && schema.path("enum").none { it == value }) return false
        if (value.isTextual) {
            val text = value.asText()
            val length = text.codePointCount(0, text.length)
            if (schema.has("minLength") && length < schema.path("minLength").asInt()) return false
            if (schema.has("maxLength") && length > schema.path("maxLength").asInt()) return false
            if (schema.has("x-max-utf16-code-units") && text.length > schema.path("x-max-utf16-code-units").asInt()) return false
            if (schema.has("pattern") && !Regex(schema.path("pattern").asText()).containsMatchIn(text)) return false
        }
        if (value.isNumber) {
            if (schema.has("minimum") && value.decimalValue() < BigDecimal(schema.path("minimum").asText())) return false
            if (schema.has("maximum") && value.decimalValue() > BigDecimal(schema.path("maximum").asText())) return false
        }
        if (value.isArray) {
            if (schema.has("maxItems") && value.size() > schema.path("maxItems").asInt()) return false
            if (schema.has("items") && value.any { !matches(schema.path("items"), it) }) return false
        }
        if (value.isObject) {
            if (schema.path("required").any { !value.has(it.asText()) }) return false
            val properties = schema.path("properties")
            val patterns = schema.path("patternProperties")
            for ((key, child) in value.properties()) {
                val validators = mutableListOf<JsonNode>()
                if (properties.has(key)) validators.add(properties.path(key))
                patterns.properties().filter { Regex(it.key).containsMatchIn(key) }.forEach { validators.add(it.value) }
                if (validators.isEmpty() && schema.has("additionalProperties")) validators.add(schema.path("additionalProperties"))
                if (validators.any { !matches(it, child) }) return false
            }
        }
        return true
    }
}
