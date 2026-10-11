package dev.nuaforma.template

import dev.nuaforma.contracts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TemplateSessionTest {
    private fun text(session: TemplateSession, source: String) = session.expand(source).joinToString("") { it.text }

    @Test
    fun `existing compiled templates still evaluate each body before visiting the next item`() {
        failure(DiagnosticCodes.MISSING_VALUE) {
            Templates.compile("{#each rows as row}{row.name}{/each}").render(
                mapOf("rows" to listOf(emptyMap<String, Any?>(), emptyMap<String, Any?>())),
                options = GenerationOptions(),
                context = GenerationContext(GenerationLimits(maxRepetitions = 1)),
            )
        }
    }

    @Test
    fun `existing text compilation does not count escaped literal braces as individual nodes`() {
        assertEquals("{".repeat(100_001), Templates.compile("{{".repeat(100_001)).render(emptyMap<String, Any?>()))
    }

    @Test
    fun `fragments retain source positions and distinguish literal data`() {
        val source = "A {name} {#if active}yes{#else}no{/if} {{x}}"
        val fragments = TemplateSession.open(mapOf("name" to "{secret}", "active" to true)).expand(source)
        assertEquals("A {secret} yes {x}", fragments.joinToString("") { it.text })
        val value = fragments.single { !it.literal }
        assertEquals("{name}", source.substring(value.sourceStart, value.sourceEnd))
        assertEquals("{secret}", value.text)
        assertEquals("yes", source.substring(fragments.single { it.text == "yes" }.sourceStart,
            fragments.single { it.text == "yes" }.sourceEnd))
        assertThrows(UnsupportedOperationException::class.java) { (fragments as MutableList).clear() }
    }

    @Test
    fun `structural scopes share root data restore aliases and expose loop metadata`() {
        val data = mutableMapOf<String, Any?>("item" to "root", "groups" to listOf(
            mapOf("name" to "A", "items" to listOf("x", "y")), mapOf("name" to "B", "items" to listOf("z"))))
        val parent = TemplateSession.open(data)
        data["item"] = "changed"
        val output = parent.repeat("groups", "item").joinToString(";") { group ->
            text(group, "{loop.number}:{item.name}") + group.repeat("item.items", "item").joinToString(",", "[", "]") {
                text(it, "{loop.index}:{item}")
            } + text(group, "{item.name}:{data.item}")
        }
        assertEquals("1:A[0:x,1:y]A:root;2:B[0:z]B:root", output)
        assertEquals("root", text(parent, "{item}"))
        assertTrue(parent.test("true || absent"))
    }

    @Test
    fun `bytes and repetitions are cumulative across paragraphs and child sessions`() {
        val byteSession = TemplateSession.open(emptyMap<String, Any?>(), context = GenerationContext(GenerationLimits(maxOutputBytes = 4)))
        assertEquals("abc", text(byteSession, "abc"))
        failure(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED) { text(byteSession, "de") }
        val repeatSession = TemplateSession.open(mapOf("rows" to listOf("x", "y")),
            context = GenerationContext(GenerationLimits(maxRepetitions = 3)))
        val children = repeatSession.repeat("rows", "row")
        assertEquals("x", text(children[0], "{row}"))
        failure(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED) { children[1].repeat("data.rows", "inner") }
    }

    @Test
    fun `structural and inline nesting consume one depth budget`() {
        val session = TemplateSession.open(mapOf("rows" to listOf("x")),
            context = GenerationContext(GenerationLimits(maxExpressionDepth = 2)))
        val child = session.repeat("rows", "row").single()
        assertEquals("x", text(child, "{row}"))
        failure(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED) { child.validate("{#if true}a{/if}") }
        failure(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED) { child.repeat("data.rows", "inner") }
        failure(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED) { child.branch() }
    }

    @Test
    fun `validation checks syntax without reading missing values or consuming output`() {
        val session = TemplateSession.open(emptyMap<String, Any?>())
        session.validate("{missing}")
        session.validateExpression("absent == true")
        failure(DiagnosticCodes.INVALID_TEMPLATE) { session.validate("{#if false}{value.method()}{/if}") }
        failure(DiagnosticCodes.INVALID_TEMPLATE) { session.validateExpression("value.method()") }
        failure(DiagnosticCodes.MISSING_VALUE) { session.test("absent") }
        failure(DiagnosticCodes.INVALID_REQUEST) { session.test("1") }
        failure(DiagnosticCodes.INVALID_TEMPLATE) { session.repeat("missing", "data") }
    }

    @Test
    fun `sessions are isolated and recheck cancellation for every operation`() {
        var cancel = false
        val first = TemplateSession.open(mapOf("name" to "first"), context = GenerationContext(cancellation = CancellationToken { cancel }))
        val second = TemplateSession.open(mapOf("name" to "second"))
        assertEquals("first", text(first, "{name}"))
        assertEquals("second", text(second, "{name}"))
        cancel = true
        failure(DiagnosticCodes.CANCELLED) { first.validate("plain") }
        failure(DiagnosticCodes.CANCELLED) { first.test("true") }
        assertEquals("second", text(second, "{name}"))
    }

    private fun failure(code: DiagnosticCode, action: () -> Unit) {
        val error = assertThrows(GenerationException::class.java, action)
        assertEquals(code, error.diagnostics.single().code)
        assertNull(error.cause)
    }
}
