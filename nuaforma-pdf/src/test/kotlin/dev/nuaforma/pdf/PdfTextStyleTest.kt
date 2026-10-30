package dev.nuaforma.pdf

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PdfTextStyleTest {
    @Test fun `style value semantics and legacy copies preserve the extra decoration`() {
        val base = PdfTextStyle("regular", 11f, PdfColor(10, 20, 30), "nl", true, 2f)
        val strike = base.withStrikethrough(true)
        assertFalse(base.strikethrough)
        assertTrue(strike.strikethrough)
        assertNotEquals(base, strike)
        assertEquals(strike, strike.copy())
        assertEquals(strike.hashCode(), strike.copy().hashCode())
        assertTrue(strike.copy(size = 14f).strikethrough)
        assertEquals(base, strike.withStrikethrough(false))
        val (id, size, color, language, underline, shift, crossed) = strike
        assertEquals(listOf("regular", 11f, PdfColor(10, 20, 30), "nl", true, 2f, true), listOf(id, size, color, language, underline, shift, crossed))
        assertEquals("PdfTextStyle(fontId=regular, size=11.0, color=PdfColor(red=10, green=20, blue=30), languageTag=nl, underline=true, baselineShift=2.0)", base.toString())
        assertNotEquals(base.copy(baselineShift = 0f), base.copy(baselineShift = -0f))
        assertEquals(2, setOf(base, strike, strike.copy()).size)
    }
    @Test fun `old Java and Kotlin default constructor and copy descriptors still execute`() {
        val type = PdfTextStyle::class.java
        val marker = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")
        val constructor = type.getConstructor(String::class.java, Float::class.javaPrimitiveType, PdfColor::class.java,
            String::class.java, Boolean::class.javaPrimitiveType, Float::class.javaPrimitiveType, Int::class.javaPrimitiveType, marker)
        val style = constructor.newInstance("regular", 11f, null, null, false, 0f, 60, null)
        assertEquals(PdfTextStyle("regular", 11f), style)
        assertNotNull(type.getConstructor(String::class.java, Float::class.javaPrimitiveType))
        val copy = type.getMethod("copy\$default", type, String::class.java, Float::class.javaPrimitiveType, PdfColor::class.java,
            String::class.java, Boolean::class.javaPrimitiveType, Float::class.javaPrimitiveType, Int::class.javaPrimitiveType, Any::class.java)
        assertEquals(style, copy.invoke(null, style, null, 0f, null, null, false, 0f, 63, null))
        val decorated = style.withStrikethrough(true)
        assertEquals(decorated, copy.invoke(null, decorated, null, 0f, null, null, false, 0f, 63, null))
    }
}
