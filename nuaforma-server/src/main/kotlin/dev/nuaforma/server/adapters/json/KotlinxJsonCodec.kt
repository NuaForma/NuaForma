package dev.nuaforma.server.adapters.json

import dev.nuaforma.contracts.*
import dev.nuaforma.server.internal.*
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.ZoneId
import java.util.Locale

internal class KotlinxJsonCodec : JsonCodec {
    override fun decode(source: ByteArray): ApiRequest = try {
        val text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(source)).toString()
        guard(text)
        val root = plain(Json.parseToJsonElement(text)) as? Map<*, *> ?: invalid()
        val objectRoot = objectValue(root)
        fields(objectRoot, setOf("input", "output", "data", "options", "providerId", "filename"))
        val input = format(objectRoot["input"])
        val output = format(objectRoot["output"])
        val options = if ("options" in objectRoot) objectValue(objectRoot["options"]) else emptyMap()
        fields(options, setOf("watermark", "locale", "timeZone", "extensions"))
        val watermark = options["watermark"]?.let { value ->
            val wm = objectValue(value)
            fields(wm, setOf("text", "color", "opacity", "rotation"))
            TextWatermark(string(wm["text"]), optionalString(wm, "color", "#B0B0B0"),
                optionalNumber(wm, "opacity", 0.25), optionalNumber(wm, "rotation", -45.0))
        }
        val locale = if ("locale" in options) Locale.Builder().setLanguageTag(string(options["locale"])).build() else Locale.ROOT
        val zone = if ("timeZone" in options) ZoneId.of(string(options["timeZone"])) else ZoneId.of("UTC")
        val extensions = if ("extensions" in options) objectValue(options["extensions"]) else emptyMap()
        val data = if ("data" in objectRoot) objectValue(objectRoot["data"]) else emptyMap()
        val provider = if ("providerId" in objectRoot) string(objectRoot["providerId"]).also {
            if (!Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}").matches(it)) invalid()
        } else null
        val filename = optionalString(objectRoot, "filename", "document")
        if (!Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}").matches(filename)) invalid()
        ApiRequest(GenerationRequirements(input, output, GenerationOptions(watermark, locale, zone, extensions)), data, provider, filename)
    } catch (failure: ApiFailure) { throw failure }
    catch (_: Exception) { invalid() }

    override fun encode(value: Any?): String = element(value).toString()

    private fun format(value: Any?): FormatRequest {
        val obj = objectValue(value)
        fields(obj, setOf("format", "options"))
        return FormatRequest(DocumentFormat(string(obj["format"])),
            if ("options" in obj) objectValue(obj["options"]) else emptyMap())
    }

    private fun plain(value: JsonElement): Any? = when (value) {
        JsonNull -> null
        is JsonObject -> value.mapValues { plain(it.value) }
        is JsonArray -> value.map(::plain)
        is JsonPrimitive -> when {
            value.isString -> value.content
            value.booleanOrNull != null -> value.boolean
            value.content.contains('.') || value.content.contains('e', true) -> BigDecimal(value.content).also {
                if (it.scale() !in -10000..10000 || it.precision() > 10000) invalid()
            }
            else -> BigInteger(value.content)
        }
    }

    private fun element(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to element(it.value) })
        is Iterable<*> -> JsonArray(value.map(::element))
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }

    private fun fields(value: Map<String, Any?>, allowed: Set<String>) { if (!allowed.containsAll(value.keys)) invalid() }
    private fun string(value: Any?): String = value as? String ?: invalid()
    @Suppress("UNCHECKED_CAST")
    private fun objectValue(value: Any?): Map<String, Any?> = value as? Map<String, Any?> ?: invalid()
    private fun optionalString(obj: Map<String, Any?>, key: String, default: String) = if (key in obj) string(obj[key]) else default
    private fun optionalNumber(obj: Map<String, Any?>, key: String, default: Double) =
        if (key in obj) (obj[key] as? Number)?.toDouble() ?: invalid() else default
    private fun invalid(): Nothing = throw ApiFailure(400, "INVALID_REQUEST", "Invalid JSON request envelope")

    /** Bound nesting/token counts and reject duplicate keys before the tree parser allocates its result. */
    private fun guard(text: String) {
        class Frame(val objectFrame: Boolean, var keyExpected: Boolean = objectFrame, val keys: MutableSet<String> = hashSetOf())
        val stack = ArrayDeque<Frame>()
        var index = 0
        var tokens = 0
        while (index < text.length) {
            val ch = text[index]
            if (ch.isWhitespace()) { index++; continue }
            if (++tokens > 100000) invalid()
            when (ch) {
                '{', '[' -> { stack.addLast(Frame(ch == '{')); if (stack.size > 64) invalid(); index++ }
                '}', ']' -> { if (stack.isEmpty()) invalid(); stack.removeLast(); index++ }
                ',' -> { stack.lastOrNull()?.let { if (it.objectFrame) it.keyExpected = true }; index++ }
                ':' -> { stack.lastOrNull()?.keyExpected = false; index++ }
                '"' -> {
                    val start = index++
                    while (index < text.length && text[index] != '"') {
                        if (text[index] == '\\') index++
                        index++
                    }
                    if (index >= text.length) invalid()
                    index++
                    stack.lastOrNull()?.let { frame -> if (frame.objectFrame && frame.keyExpected) {
                        val key = Json.parseToJsonElement(text.substring(start, index)).jsonPrimitive.content
                        if (!frame.keys.add(key)) invalid()
                    } }
                }
                else -> {
                    val start = index
                    while (index < text.length && text[index] !in ",]} \r\n\t:") index++
                    if (index == start || index - start > 10000) invalid()
                }
            }
        }
        if (stack.isNotEmpty()) invalid()
    }
}
