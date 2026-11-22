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
}
