package dev.nuaforma.contracts

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.math.BigDecimal

class RequestTest {
    private val requirements = GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.PDF))

    @Test
    fun `data is complete deeply copied and read only`() {
        val customer = linkedMapOf<String, Any?>("name" to "Example", "privateField" to "supplied deliberately")
        val rows = mutableListOf<Any?>(customer, null, BigDecimal("1.23"))
        val original = linkedMapOf<String, Any?>("rows" to rows, "class" to "a literal key", "" to true)
        val request = GenerationRequest(ByteArrayInputStream(byteArrayOf()), requirements, original)
        customer["name"] = "changed"
        rows.clear()
        original.clear()

        val copiedRows = request.data["rows"] as List<*>
        val copiedCustomer = copiedRows[0] as Map<*, *>
        assertEquals("Example", copiedCustomer["name"])
        assertEquals("supplied deliberately", copiedCustomer["privateField"])
        assertEquals("a literal key", request.data["class"])
        assertTrue(request.data.containsKey(""))
        assertNull(copiedRows[1])
        assertEquals(BigDecimal("1.23"), copiedRows[2])
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (copiedCustomer as MutableMap<String, Any?>)["name"] = "changed"
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (copiedRows as MutableList<Any?>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (request.data as MutableMap<String, Any?>).clear()
        }
    }

    @Test
    fun `request does not consume streams or evaluate values`() {
        val template = ByteArrayInputStream(byteArrayOf(1, 2, 3))
        val request = GenerationRequest(template, requirements, mapOf("literal" to "{customer.secret}"))
        assertEquals(3, template.available())
        assertEquals("{customer.secret}", request.data["literal"])
        assertSame(template, request.template)
    }

    @Test
    fun `shared containers are allowed but cycles are rejected`() {
        val shared = mapOf("value" to 1)
        val request = GenerationRequest(ByteArrayInputStream(byteArrayOf()), requirements, mapOf("a" to shared, "b" to shared))
        assertEquals(request.data["a"], request.data["b"])
        val cyclic = linkedMapOf<String, Any?>()
        cyclic["self"] = cyclic
        assertThrows(IllegalArgumentException::class.java) { FormatRequest(DocumentFormat.PDF, cyclic) }
        val list = mutableListOf<Any?>()
        list.add(list)
        assertThrows(IllegalArgumentException::class.java) {
            GenerationRequest(ByteArrayInputStream(byteArrayOf()), requirements, mapOf("cycle" to list))
        }
    }

    @Test
    fun `non JSON values and non finite numbers are rejected without printing their values`() {
        for (value in listOf(Any(), Double.NaN, Double.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, setOf(1))) {
            assertThrows(IllegalArgumentException::class.java) {
                GenerationRequest(ByteArrayInputStream(byteArrayOf()), requirements, mapOf("item" to value))
            }
        }
        val error = assertThrows(IllegalArgumentException::class.java) {
            FormatRequest(DocumentFormat.PDF, mapOf("secret" to object {
                override fun toString() = "NEVER_PRINT_THIS"
            }))
        }
        assertFalse(error.message!!.contains("NEVER_PRINT_THIS"))
    }

    @Test
    fun `construction is bounded for deep and wide value graphs`() {
        var nested: Any? = null
        repeat(66) { nested = listOf(nested) }
        assertThrows(IllegalArgumentException::class.java) { FormatRequest(DocumentFormat.PDF, mapOf("item" to nested)) }
        assertThrows(IllegalArgumentException::class.java) {
            FormatRequest(DocumentFormat.PDF, mapOf("item" to List(100_001) { 1 }))
        }
    }

    @Test
    fun `input output and general options are isolated from data`() {
        val source = linkedMapOf<String, Any?>("version" to "1.7", "custom" to listOf(mapOf("enabled" to true)))
        val output = FormatRequest(DocumentFormat.PDF, source)
        source["version"] = "2.0"
        val options = GenerationOptions(TextWatermark("CONCEPT"), extensions = mapOf("customSetting" to 42))
        val request = GenerationRequest(
            ByteArrayInputStream(byteArrayOf()),
            GenerationRequirements(FormatRequest(DocumentFormat.DOCX), output, options),
            mapOf("reference" to "NF-001"),
        )
        assertEquals("1.7", request.requirements.output.options["version"])
        assertEquals(setOf("reference"), request.data.keys)
        assertEquals("CONCEPT", request.requirements.options.watermark!!.text)
        assertEquals(42, request.requirements.options.extensions["customSetting"])
        assertEquals("UTC", options.timeZone.id)
        assertEquals(java.util.Locale.ROOT, options.locale)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (output.options as MutableMap<String, Any?>).clear()
        }
    }

    @Test
    fun `watermark settings are optional validated and deterministic`() {
        assertNull(GenerationOptions().watermark)
        val watermark = TextWatermark("CONCEPT")
        assertEquals("#B0B0B0", watermark.color)
        assertEquals(0.25, watermark.opacity)
        assertEquals(-45.0, watermark.rotation)
        assertThrows(IllegalArgumentException::class.java) { TextWatermark("") }
        assertThrows(IllegalArgumentException::class.java) { TextWatermark("x".repeat(4097)) }
        assertThrows(IllegalArgumentException::class.java) { TextWatermark("draft", "red") }
        assertThrows(IllegalArgumentException::class.java) { TextWatermark("draft", opacity = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { TextWatermark("draft", opacity = 1.1) }
        assertThrows(IllegalArgumentException::class.java) { TextWatermark("draft", rotation = 361.0) }
    }

    @Test
    fun `resource names are logical identifiers and factories are not called during construction`() {
        var opens = 0
        val sources = linkedMapOf("font-1" to ResourceSource { opens++; ByteArrayInputStream(byteArrayOf(1)) })
        val request = GenerationRequest(ByteArrayInputStream(byteArrayOf()), requirements, resources = sources)
        sources.clear()
        assertEquals(0, opens)
        assertEquals(setOf("font-1"), request.resources.keys)
        request.resources.getValue("font-1").openStream().use { assertEquals(1, it.read()) }
        assertEquals(1, opens)
        assertThrows(IllegalArgumentException::class.java) {
            GenerationRequest(ByteArrayInputStream(byteArrayOf()), requirements, resources = mapOf("../font" to ResourceSource {
                ByteArrayInputStream(byteArrayOf())
            }))
        }
    }
}
