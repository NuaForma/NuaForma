package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import javax.imageio.ImageIO

class WordSvgPictureTest {
    private val namespace = "http://www.w3.org/2000/svg"
    private val context = GenerationContext()

    private fun render(body: String, view: String = "0 0 200 100"): ByteArray =
        WordSvgPicture.rasterize("""<svg xmlns="$namespace" viewBox="$view">$body</svg>""".toByteArray(), context) {
            throw IllegalArgumentException(it)
        }

    @Test
    fun `static filled line and cubic paths retain color and transparency`() {
        val shape = """<g><path fill="#25a" d="M20 20L80 20C100 20 100 80 80 80L20 80Z"/></g>
            <path fill="#19ee53" d="M120 20 180 20 180 80 120 80 Z"/>"""
        val image = render(shape).inputStream().use(ImageIO::read)
        assertEquals(1024, image.width)
        assertEquals(512, image.height)
        assertEquals(0, image.getRGB(24, 24))
        assertEquals(0xFF2255AA.toInt(), image.getRGB(220, 250))
        assertEquals(0xFF19EE53.toInt(), image.getRGB(800, 250))
    }

    @Test
    fun `unsupported properties malformed paths and invisible documents reject`() {
        for (body in listOf("<g/>", """<path fill="none" d="M0 0L20 20Z"/>""",
            """<path stroke="#333" d="M0 0L20 20Z"/>""", """<path d="M0 0 Q20 20 40 40"/>""",
            """<path d="M 0, 0 L20,,30 Z"/>""", """<path d="M,0 0L20 30Z"/>""",
            """<path d="M0 0L20"/>""", """<g transform="scale(2)"><path d="M0 0L20 20Z"/></g>""",
            """<path d="M0 0 L1e8 20Z"/>""", "<foreignObject/>")) {
            assertThrows(IllegalArgumentException::class.java) { render(body) }
        }
        assertThrows(IllegalArgumentException::class.java) { render("""<path d="M0 0L1 1Z"/>""", "0 0 200 0") }
    }

    private fun readSvg(source: String): ByteArray = WordSvgPicture.rasterize(source.toByteArray(), context) {
        throw IllegalArgumentException(it)
    }

    @Test
    fun `viewport properties and both portrait and landscape scaling remain bounded`() {
        val path = """<path d="M-10 -10 10 -10 10 10 -10 10Z"/>"""
        val svg = """<svg xmlns="$namespace" id="Fixture" xml:space="preserve" overflow="hidden" viewBox="-20 -40 40 80">$path</svg>"""
        val picture = readSvg(svg).inputStream().use(ImageIO::read)
        assertEquals(512, picture.width)
        assertEquals(1024, picture.height)
        assertEquals(0xFF000000.toInt(), picture.getRGB(256, 512))
        assertEquals(0, picture.getRGB(10, 10))
        for (source in listOf(svg.replace(namespace, "urn:else"), svg.replace("<svg ", "<drawing ").replace("</svg>", "</drawing>"),
            svg.replace("xml:space", "xml:base"), svg.replace("preserve", "random"), svg.replace("Fixture", ""),
            svg.replace("Fixture", "x".repeat(65)), svg.replace("Fixture", "Fixture&#x9;"),
            svg.replace("hidden", "scale"), svg.replace("overflow=", "unknown="),
            svg.replace("id=", "other:id=").replace("<svg ", "<svg xmlns:other=\"urn:else\" ")))
            assertEquals("Unsupported SVG viewport properties", assertThrows(IllegalArgumentException::class.java) { readSvg(source) }.message)
        assertEquals("Missing SVG view box", assertThrows(IllegalArgumentException::class.java) {
            readSvg(svg.replace(" viewBox=\"-20 -40 40 80\"", ""))
        }.message)
        for (view in listOf("0 0 40", "0 0 .001 100", "0 0 100 .001")) {
            assertEquals("Invalid SVG view box", assertThrows(IllegalArgumentException::class.java) { render(path, view) }.message)
        }
        for (view in listOf("0 0 1 1000", "0 0 1000 1")) {
            assertEquals("SVG picture dimensions exceed the qualified profile", assertThrows(IllegalArgumentException::class.java) {
                render(path, view)
            }.message)
        }
    }

    @Test
    fun `malformed and excessive SVG shapes fail at their own guard`() {
        val path = """<path d="M0 0L1 1Z"/>"""
        val cases = listOf(
            " ".repeat(131_073) to "SVG picture byte budget exceeded",
            "<g>text$path</g>" to "Unsupported nested SVG picture structure",
            "<g>".repeat(9) + path + "</g>".repeat(9) to "Unsupported nested SVG picture structure",
            "<g/>".repeat(257) to "Unsupported SVG picture element",
            "<g xmlns=\"urn:foreign\"/>" to "Unsupported SVG picture element",
            "<path><g/></path>" to "Unsupported SVG path properties",
            "<path>text</path>" to "Unsupported SVG path properties",
            "<path xmlns:other=\"urn:foreign\" other:d=\"M0 0 L1 1 Z\"/>" to "Unsupported SVG path properties",
            "<path fill=\"teal\" d=\"M0 0L1 1Z\"/>" to "Unsupported SVG path fill",
            "<path/>" to "Missing SVG path",
            "<path d=\"\"/>" to "SVG path size is outside the qualified budget",
            "<path d=\"0 0 L1 1\"/>" to "Missing SVG path command",
            "<path d=\"L0 0 Z\"/>" to "SVG path requires a starting point",
            "<path d=\"M0 0 L1 1 Z,\"/>" to "Incomplete SVG path data",
            "<path d=\"M0 0,L1 1\"/>" to "Malformed SVG path separators",
            "<path d=\"M0 0 L1e999 1\"/>" to "Invalid SVG coordinate",
            "<path d=\"M0 0 L${"0".repeat(25)} 1\"/>" to "Invalid SVG coordinate",
            "<path d=\"M0 0 ${"L1 1 ".repeat(4097)}Z\"/>" to "SVG picture path budget exceeded",
        )
        for ((body, reason) in cases)
            assertEquals(reason, assertThrows(IllegalArgumentException::class.java) { render(body) }.message)
    }
}
