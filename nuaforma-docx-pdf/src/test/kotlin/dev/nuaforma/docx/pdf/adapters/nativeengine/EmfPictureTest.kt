package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.adapters.nativeengine.emf.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Font
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

class EmfPictureTest {

    private fun decode(source: ByteArray, inspect: Boolean = false): ByteArray? =
        EmfPicture(GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)), null).decode(source, 100f, 50f, inspect)

    @Test
    fun `classic descriptions are not OpenGL fields and all supported fixed header layouts render identically`() {
        val base = EmfFixture.logo()
        val expected = requireNotNull(decode(base))
        for (fixed in listOf(88, 100, 108)) for (description in listOf("", "X", "Synthetic application\u0000Large logo description\u0000")) {
            val source = EmfFixture.withHeader(base, fixed, description)
            assertArrayEquals(expected, decode(source), "fixed=$fixed")
            assertNull(decode(source, true))
        }
    }

    @Test
    fun `bounded descriptions without terminators are opaque metadata in all header layouts`() {
        val expected = requireNotNull(decode(EmfFixture.logo()))
        for (fixed in listOf(88, 100, 108)) for (text in listOf("X", "Synthetic application\u0000Logo", "X".repeat(1024), "{if neverEvaluated}")) {
            val source = EmfFixture.withHeader(EmfFixture.logo(), fixed, text, terminate = false)
            assertArrayEquals(expected, decode(source), "fixed=$fixed length=${text.length}")
            assertNull(decode(source, true))
        }
        val surrogate = EmfFixture.withHeader(EmfFixture.logo(), 108, "X", terminate = false)
        ByteBuffer.wrap(surrogate).order(ByteOrder.LITTLE_ENDIAN).putShort(108, 0xd800.toShort())
        assertArrayEquals(expected, decode(surrogate))
        assertNull(decode(surrogate, true))
    }

    @Test
    fun `actual OpenGL pixel formats and malformed description ranges remain rejected`() {
        val valid = EmfFixture.withHeader(EmfFixture.logo(), 108)
        for ((offset, value) in listOf(96 to 1, 88 to 40, 92 to 108, 60 to 1025, 64 to 87,
            64 to Int.MAX_VALUE, 100 to -1)) {
            val source = valid.copyOf()
            ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value)
            assertThrows(EmfFailure::class.java) { decode(source) }
        }
        val gl = EmfFixture.withHeader(EmfFixture.file(EmfFixture.record(102, 0)), 88, "Description")
        assertTrue(assertThrows(EmfFailure::class.java) { decode(gl) }.message!!.contains("102"))
    }

    @Test
    fun `opaque descriptions do not permit invalid ranges OpenGL or unsafe records`() {
        val source = EmfFixture.withHeader(EmfFixture.logo(), 108, "No terminator", terminate = false)
        val size = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN).getInt(4)
        for ((offset, value) in listOf(60 to -1, 60 to 1025, 60 to 0, 64 to 0, 64 to 87,
            64 to Int.MAX_VALUE, 64 to size - 2, 96 to 1, 88 to 40, 92 to 108, 100 to -1)) {
            val invalid = source.copyOf()
            ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value)
            for (inspect in listOf(false, true)) assertThrows(EmfFailure::class.java) { decode(invalid, inspect) }
        }
        for (record in listOf(EmfFixture.record(102, 0), EmfFixture.record(70, 4, 123),
            EmfFixture.record(105, 0), EmfFixture.record(37, 7))) {
            val invalid = EmfFixture.withHeader(EmfFixture.file(record), 88, "No terminator", terminate = false)
            for (inspect in listOf(false, true)) assertThrows(EmfFailure::class.java) { decode(invalid, inspect) }
        }
    }

    @Test
    fun `vector logo renders at bounded resolution with transparent surround and distinct fill`() {
        val png = requireNotNull(decode(EmfFixture.logo()))
        val image = ImageIO.read(png.inputStream())
        assertEquals(400, image.width)
        assertEquals(200, image.height)
        assertEquals(0, image.getRGB(0, 0) ushr 24)
        assertEquals(Color.WHITE.rgb, image.getRGB(100, 100))
        assertEquals(Color(16, 112, 176).rgb, image.getRGB(300, 100))
        assertNull(decode(EmfFixture.logo(), true))
    }

    @Test
    fun `unsupported and malformed records reject explicitly without executing extensions`() {
        for (source in listOf(
            EmfFixture.file(EmfFixture.record(70, 4, 123)),
            EmfFixture.file(EmfFixture.record(105, 0)),
            EmfFixture.file(EmfFixture.record(37, 7)),
            EmfFixture.file(EmfFixture.record(34, -1)),
            EmfFixture.file(EmfFixture.record(43, 20, 20, 10, 10)),
            EmfFixture.file(EmfFixture.record(60)),
            byteArrayOf(1, 2, 3),
            EmfFixture.logo().copyOf(100)
        )) {
            val failure = assertThrows(EmfFailure::class.java) { decode(source) }
            assertTrue(failure.message!!.startsWith("EMF:"))
        }
    }

    @Test
    fun `drawing paths saved contexts mapping and clips retain request local state`() {
        val source = EmfFixture.file(
            EmfFixture.record(33), EmfFixture.record(9, 200, 100), EmfFixture.record(11, 200, 100),
            EmfFixture.record(30, 0, 0, 200, 100), EmfFixture.record(28),
            EmfFixture.record(59), EmfFixture.record(27, 20, 20), EmfFixture.record(54, 180, 20),
            EmfFixture.record(54, 100, 80), EmfFixture.record(61), EmfFixture.record(60),
            EmfFixture.record(63, 0, 0, 200, 100), EmfFixture.record(34, -1)
        )
        val expected = requireNotNull(decode(source))
        assertArrayEquals(expected, decode(source))
        assertNull(decode(source, true))
    }

    @Test
    fun `image text requires supplied physical font while structural inspection needs none`() {
        val source = EmfFixture.file(EmfFixture.font(), EmfFixture.record(37, 1), EmfFixture.text("Logo"))
        assertNull(decode(source, true))
        assertThrows(EmfFailure::class.java) { decode(source) }
        val font = requireNotNull(javaClass.getResourceAsStream("/own-engine-fonts/LiberationSans-Regular.ttf")).use {
            Font.createFont(Font.TRUETYPE_FONT, it)
        }
        val png = EmfPicture(GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))) { family, bold, italic ->
            assertEquals("Liberation Sans", family); assertFalse(bold); assertFalse(italic); font
        }.decode(source, 100f, 50f, false)
        assertNotNull(png)
        assertThrows(EmfFailure::class.java) { decode(EmfFixture.file(EmfFixture.font(), EmfFixture.record(37, 1), EmfFixture.text("Secret", 0x1000)), true) }
    }

    @Test
    fun `record offsets and allocation limits cannot escape their bounds`() {
        val broken = EmfFixture.logo().copyOf()
        ByteBuffer.wrap(broken).order(ByteOrder.LITTLE_ENDIAN).putInt(92, Int.MAX_VALUE)
        assertThrows(EmfFailure::class.java) { decode(broken) }
        assertThrows(GenerationException::class.java) {
            EmfPicture(GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)), null).decode(EmfFixture.logo(), 4000f, 4000f, false)
        }
    }

    @Test
    fun `embedded bitmap has actual source pixels and validates source ranges`() {
        val bitmap = EmfFixture.bitmap()
        val image = ImageIO.read(requireNotNull(decode(EmfFixture.file(bitmap))).inputStream())
        assertEquals(Color.RED.rgb, image.getRGB(60, 60))
        assertNull(decode(EmfFixture.file(bitmap), true))
        val malformed = bitmap.copyOf()
        ByteBuffer.wrap(malformed).order(ByteOrder.LITTLE_ENDIAN).putInt(48, Int.MAX_VALUE)
        assertThrows(EmfFailure::class.java) { decode(EmfFixture.file(malformed)) }
    }

    @Test
    fun `explicit mapping transforms and clip changes produce bounded deterministic output`() {
        val one = 1f.toRawBits(); val zero = 0f.toRawBits()
        val source = EmfFixture.file(
            EmfFixture.record(16, 0), EmfFixture.record(17, 8), EmfFixture.record(18, 1),
            EmfFixture.record(19, 2), EmfFixture.record(20, 13), EmfFixture.record(21, 4), EmfFixture.record(22, 24),
            EmfFixture.record(24, 0), EmfFixture.record(25, 0xffffff),
            EmfFixture.record(10, 0, 0), EmfFixture.record(12, 0, 0),
            EmfFixture.record(31, 2, 2, 2, 2), EmfFixture.record(32, 2, 2, 2, 2),
            EmfFixture.record(35, one, zero, zero, one, zero, zero),
            EmfFixture.record(36, one, zero, zero, one, zero, zero, 2),
            EmfFixture.record(36, one, zero, zero, one, zero, zero, 3),
            EmfFixture.record(36, one, zero, zero, one, zero, zero, 4),
            EmfFixture.record(36, zero, zero, zero, zero, zero, zero, 1),
            EmfFixture.record(58, 10f.toRawBits()),
            EmfFixture.record(30, 0, 0, 200, 100), EmfFixture.record(26, 1, 1), EmfFixture.record(28),
            EmfFixture.record(29, 0, 0, 5, 5), EmfFixture.record(28),
            EmfFixture.record(44, 20, 20, 180, 80, 10, 10), EmfFixture.record(70, 0)
        )
        assertNotNull(decode(source))
        assertNull(decode(source, true))
    }

    @Test
    fun `polygons and bezier paths support regular and compact coordinates`() {
        for (type in listOf(2, 3, 4, 5, 6, 7, 8, 85, 86, 87, 88, 89, 90, 91)) {
            val basic = if (type >= 85) type - 83 else type
            val values = if (basic == 5) listOf(30 to 20, 80 to 20, 90 to 70)
                else listOf(20 to 20, 80 to 20, 90 to 70, 40 to 80)
            val grouped = basic in 7..8
            val start = if (grouped) 36 else 28
            val step = if (type >= 85) 4 else 8
            val record = ByteBuffer.allocate(start + values.size * step).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(type); putInt(capacity()); position(24)
                if (grouped) putInt(1)
                putInt(values.size)
                if (grouped) putInt(values.size)
                for ((x, y) in values) if (step == 4) { putShort(x.toShort()); putShort(y.toShort()) } else { putInt(x); putInt(y) }
            }.array()
            assertNotNull(decode(EmfFixture.file(EmfFixture.record(27, 10, 10), record)), "record $type")
        }
    }

    @Test
    fun `pen styles stock objects and lifecycle preserve deterministic brush fills`() {
        for (style in 0..5) {
            val source = EmfFixture.file(EmfFixture.record(38, 1, style, 3, 0, 0xff), EmfFixture.record(37, 1),
                EmfFixture.record(43, 20, 20, 180, 80), EmfFixture.record(37, 0x80000007.toInt()), EmfFixture.record(40, 1))
            assertNotNull(decode(source))
        }
        for (stock in 0..8) assertNull(decode(EmfFixture.file(EmfFixture.record(37, 0x80000000.toInt() or stock)), true))
        for (style in listOf(0x10000, 0x11101, 0x12202, 0x10007)) {
            val pen = EmfFixture.record(95, 1, 0, 0, 0, 0, style, 2, 0, 0xff, 0, if (style and 15 == 7) 2 else 0, 3, 1)
            assertNotNull(decode(EmfFixture.file(pen, EmfFixture.record(37, 1), EmfFixture.record(43, 20, 20, 180, 80))))
        }
    }

    @Test
    fun `solid extended pens ignore hatch and absent bitmap offsets without changing pixels`() {
        val baseline = EmfFixture.logoWithPen(EmfFixture.extendedPen())
        val expected = requireNotNull(decode(baseline))
        for ((offset, value) in listOf(12 to 52, 20 to 52, 12 to Int.MIN_VALUE, 20 to -1, 44 to -1)) {
            val pen = EmfFixture.extendedPen()
            ByteBuffer.wrap(pen).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value)
            val source = EmfFixture.logoWithPen(pen)
            assertArrayEquals(expected, decode(source), "field offset=$offset")
            assertNull(decode(source, true))
        }
    }

    @Test
    fun `BS_NULL with PS_NULL ignores its color and hatch and paints no stroke`() {
        val pen = EmfFixture.extendedPen(style = 5, brush = 1, color = -1)
        ByteBuffer.wrap(pen).order(ByteOrder.LITTLE_ENDIAN).apply { putInt(12, 52); putInt(20, 52); putInt(44, -1) }
        val source = EmfFixture.logoWithPen(pen)
        assertArrayEquals(requireNotNull(decode(EmfFixture.logo())), decode(source))
        assertNull(decode(source, true))
    }

    @Test
    fun `extended pen failures identify actual brush styles payloads and invalid null combinations`() {
        val styles = listOf(2 to "BS_HATCHED", 3 to "BS_PATTERN", 4 to "BS_INDEXED", 5 to "BS_DIBPATTERN",
            6 to "BS_DIBPATTERNPT", 7 to "BS_PATTERN8X8", 8 to "BS_DIBPATTERN8X8", 9 to "BS_MONOPATTERN", -1 to "UNKNOWN")
        val cases = styles.map { (value, name) -> Triple(36, value, "BrushStyle=${Integer.toUnsignedString(value)} ($name)") } +
            listOf(Triple(16, 40, "cbBmi=40"), Triple(24, 4, "cbBits=4"), Triple(16, -1, "cbBmi=4294967295"),
                Triple(24, -1, "cbBits=4294967295"), Triple(36, 1, "BS_NULL requires PS_NULL"))
        for ((offset, value, reason) in cases) {
            val pen = EmfFixture.extendedPen()
            ByteBuffer.wrap(pen).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value)
            for (inspect in listOf(false, true)) {
                val error = assertThrows(EmfFailure::class.java) { decode(EmfFixture.logoWithPen(pen), inspect) }
                assertTrue(error.message!!.contains(reason), error.message)
                assertTrue(error.message!!.contains("record 95 at"), error.message)
            }
        }
    }

    @Test
    fun `extended pen metadata cannot bypass meaningful flags widths or dash limits`() {
        for ((offset, value) in listOf(28 to 0x40000000, 32 to -1, 32 to 1_000_001, 48 to -1)) {
            val pen = EmfFixture.extendedPen()
            ByteBuffer.wrap(pen).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value)
            assertThrows(EmfFailure::class.java) { decode(EmfFixture.logoWithPen(pen), true) }
        }
        val many = EmfFixture.extendedPen()
        ByteBuffer.wrap(many).order(ByteOrder.LITTLE_ENDIAN).putInt(48, 33)
        val limited = assertThrows(GenerationException::class.java) { decode(EmfFixture.logoWithPen(many), true) }
        assertEquals(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, limited.diagnostics.single().code)
        val truncated = EmfFixture.extendedPen().copyOf(48)
        ByteBuffer.wrap(truncated).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 48)
        assertTrue(assertThrows(EmfFailure::class.java) { decode(EmfFixture.logoWithPen(truncated), true) }.message!!.contains("Truncated"))
        val missingDash = EmfFixture.extendedPen(style = 7)
        ByteBuffer.wrap(missingDash).order(ByteOrder.LITTLE_ENDIAN).putInt(48, 1)
        assertThrows(EmfFailure::class.java) { decode(EmfFixture.logoWithPen(missingDash), true) }
    }

    @Test
    fun `invalid graphics state and malformed handles are rejected in inspection too`() {
        val invalid = listOf(
            EmfFixture.record(16, 1), EmfFixture.record(17, 99), EmfFixture.record(18, 3),
            EmfFixture.record(19, 4), EmfFixture.record(20, 3), EmfFixture.record(21, 5),
            EmfFixture.record(22, 256), EmfFixture.record(22, 4), EmfFixture.record(22, 16),
            EmfFixture.record(24, -1),
            EmfFixture.record(31, 1, 0, 1, 1), EmfFixture.record(58, Float.NaN.toRawBits()),
            EmfFixture.record(40, 1), EmfFixture.record(39, 0, 0, 0, 0), EmfFixture.record(39, 1, 3, 0, 0),
            EmfFixture.record(38, 1, 9, 1, 0, 0), EmfFixture.record(37, 0x80000020.toInt())
        )
        for (record in invalid) assertThrows(EmfFailure::class.java) { decode(EmfFixture.file(record), true) }
        assertThrows(EmfFailure::class.java) { decode(EmfFixture.file(EmfFixture.record(17, 8), EmfFixture.record(9, 0, 10)), true) }
    }

    @Test
    fun `request caches respect cancellation byte limits and distinct raster sizes`() {
        val source = EmfFixture.logo()
        val images = NativeEmfImages(GenerationContext(), null, false)
        val first = images.decode(source, 100f, 50f)
        assertSame(first, images.decode(source, 100f, 50f))
        assertFalse(first.contentEquals(images.decode(source, 40f, 20f)))
        val inspection = NativeEmfImages(GenerationContext(), null, true)
        assertSame(source, inspection.decode(source, 100f, 50f))
        val cancelled = assertThrows(GenerationException::class.java) {
            NativeEmfImages(GenerationContext(cancellation = CancellationToken { true }), null, false).decode(source, 100f, 50f)
        }
        assertEquals(DiagnosticCodes.CANCELLED, cancelled.diagnostics.single().code)
        assertThrows(GenerationException::class.java) {
            NativeEmfImages(GenerationContext(GenerationLimits(maxExpandedBytes = 1000)), null, false).decode(source, 100f, 50f)
        }
    }

    @Test
    fun `concurrent translations share no graphics objects state or pixels`() {
        val source = EmfFixture.logo()
        val expected = requireNotNull(decode(source))
        val executor = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val requests = (1..12).map { executor.submit<ByteArray> {
                NativeEmfImages(GenerationContext(), null, false).decode(source, 100f, 50f)
            } }
            requests.forEach { assertArrayEquals(expected, it.get(10, java.util.concurrent.TimeUnit.SECONDS)) }
        } finally { executor.shutdownNow() }
    }

    @Test
    fun `positioned text supports supplied glyphs ANSI advances rotation and decorations`() {
        val physical = requireNotNull(javaClass.getResourceAsStream("/own-engine-fonts/LiberationSans-Regular.ttf")).use { Font.createFont(Font.TRUETYPE_FONT, it) }
        val font = EmfFixture.font().apply {
            ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putInt(12, 20).putInt(20, 900).putInt(24, 900)
            this[33] = 1; this[34] = 1
        }
        val vector = physical.createGlyphVector(java.awt.font.FontRenderContext(null, true, true), "Logo")
        val glyphs = CharArray(vector.numGlyphs) { vector.getGlyphCode(it).toChar() }.concatToString()
        val texts = listOf(EmfFixture.text("Logo", 2 or 4, IntArray(4) { 12 }),
            EmfFixture.text("Logo", 8192, intArrayOf(12, 1, 12, 1, 12, 1, 12, 1)),
            EmfFixture.text(glyphs, 16, IntArray(4) { 12 }), EmfFixture.text("Logo", wide = false))
        for (text in texts) for (alignment in listOf(0, 2, 6, 8, 24, 31)) {
            val source = EmfFixture.file(font, EmfFixture.record(37, 1), EmfFixture.record(27, 20, 50),
                EmfFixture.record(22, alignment), text)
            assertNull(decode(source, true))
            val rendered = EmfPicture(GenerationContext()) { _, _, _ -> physical }.decode(source, 100f, 50f, false)
            assertNotNull(rendered)
        }
    }

    @Test
    fun `noncommuting transforms match GDI left and right multiplication positions`() {
        // Independently checked with Set/Modify/GetWorldTransform: mode 2 yields dx=20, mode 3 dx=15.
        val one = 1f.toRawBits(); val two = 2f.toRawBits(); val zero = 0f.toRawBits()

        fun raster(mode: Int) = ImageIO.read(requireNotNull(decode(EmfFixture.file(
            EmfFixture.record(35, two, zero, zero, two, 10f.toRawBits(), zero),
            EmfFixture.record(36, one, zero, zero, one, 5f.toRawBits(), zero, mode),
            EmfFixture.record(43, 20, 20, 40, 40)
        ))).inputStream())

        assertEquals(0, raster(2).getRGB(114, 100) ushr 24)
        assertEquals(Color.WHITE.rgb, raster(3).getRGB(114, 100))
    }

    @Test
    fun `text map mode ignores extent changes while anisotropic mode scales the same geometry`() {

        fun raster(mode: Int) = ImageIO.read(requireNotNull(decode(EmfFixture.file(
            EmfFixture.record(17, mode), EmfFixture.record(9, 200, 100), EmfFixture.record(11, 100, 50),
            EmfFixture.record(43, 20, 20, 40, 40)
        ))).inputStream())

        assertEquals(Color.WHITE.rgb, raster(1).getRGB(60, 60))
        assertEquals(0, raster(8).getRGB(60, 60) ushr 24)
    }

    @Test
    fun `PNG transport refuses an encoded payload above the request byte limit`() {
        val image = java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val error = assertThrows(GenerationException::class.java) {
            EmfPng.encode(image, GenerationContext(GenerationLimits(maxExpandedBytes = 60)))
        }
        assertEquals(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, error.diagnostics.single().code)
    }

    @Test
    fun `mutated untrusted records produce only bounded declared failures or valid inspection`() {
        val sources = listOf(EmfFixture.logo(), EmfFixture.file(EmfFixture.bitmap()),
            EmfFixture.file(EmfFixture.font(), EmfFixture.record(37, 1), EmfFixture.text("Logo")))
        val random = java.util.Random(196)
        var rejected = 0
        for (original in sources) repeat(400) {
            val changed = original.copyOf()
            val at = random.nextInt(changed.size)
            changed[at] = (changed[at].toInt() xor (1 shl random.nextInt(8))).toByte()
            try {
                assertNull(decode(changed, true))
            } catch (_: EmfFailure) {
                rejected++
            } catch (error: GenerationException) {
                assertTrue(error.diagnostics.all { it.code == DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED })
                rejected++
            }
        }
        assertTrue(rejected > 400, "Mutated structural fields must actually exercise rejection paths")
    }
}
