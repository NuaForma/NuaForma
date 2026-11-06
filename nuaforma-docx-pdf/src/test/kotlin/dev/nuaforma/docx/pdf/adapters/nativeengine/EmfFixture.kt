package dev.nuaforma.docx.pdf.adapters.nativeengine

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Public synthetic GDI logo, unrelated to any user document or branding. */
internal object EmfFixture {

    fun record(type: Int, vararg values: Int): ByteArray = buffer(8 + values.size * 4).apply {
        putInt(type); putInt(capacity()); values.forEach(::putInt)
    }.array()

    fun file(vararg records: ByteArray): ByteArray {
        val end = record(14, 0, 0, 20)
        val size = 88 + records.sumOf { it.size } + end.size
        val header = buffer(88).apply {
            putInt(1); putInt(88)
            listOf(0, 0, 200, 100, 0, 0, 20000, 10000, 0x464d4520, 0x10000, size, records.size + 2).forEach(::putInt)
            putShort(16); putShort(0)
            listOf(0, 0, 0, 1000, 1000, 1000, 1000).forEach(::putInt)
        }.array()
        return header + records.fold(byteArrayOf()) { result, item -> result + item } + end
    }

    fun withHeader(source: ByteArray, fixed: Int = 88, description: String = "Synthetic logo", terminate: Boolean = true) : ByteArray {
        require(fixed in setOf(88, 100, 108))
        val old = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN).getInt(4)
        val text = if (description.isEmpty()) byteArrayOf() else (description + if (terminate) "\u0000" else "").toByteArray(Charsets.UTF_16LE)
        val size = (fixed + text.size + 3) / 4 * 4
        val header = buffer(size).apply {
            put(source, 0, 88); putInt(4, size); putInt(48, source.size - old + size)
            putInt(60, text.size / 2); putInt(64, if (text.isEmpty()) 0 else fixed)
            position(fixed); put(text)
        }.array()
        return header + source.copyOfRange(old, source.size)
    }

    fun logo(): ByteArray = file(
        record(39, 1, 0, 0x00b07010, 0), record(37, 1), record(37, 0x80000008.toInt()),
        record(43, 10, 10, 190, 90), record(37, 0x80000000.toInt()), record(42, 25, 25, 75, 75)
    )

    fun font(): ByteArray = buffer(332).apply {
        putInt(82); putInt(capacity()); putInt(1)
        putInt(-20); putInt(0); putInt(0); putInt(0); putInt(400)
        put(0); put(0); put(0); put(1)
        position(40); put("Liberation Sans".toByteArray(Charsets.UTF_16LE))
    }.array()

    fun text(value: String, flags: Int = 0, advances: IntArray? = null, wide: Boolean = true): ByteArray {
        val chars = value.toByteArray(if (wide) Charsets.UTF_16LE else Charsets.US_ASCII)
        val end = (76 + chars.size + 3) / 4 * 4
        return buffer(end + (advances?.size ?: 0) * 4).apply {
            putInt(if (wide) 84 else 83); putInt(capacity()); position(24); putInt(1)
            putFloat(1f); putFloat(1f); putInt(20); putInt(50)
            putInt(value.length); putInt(76); putInt(flags)
            putInt(0); putInt(0); putInt(200); putInt(100); putInt(if (advances == null) 0 else end)
            position(76); put(chars)
            if (advances != null) { position(end); advances.forEach(::putInt) }
        }.array()
    }

    fun bitmap(): ByteArray = buffer(124).apply {
        putInt(81); putInt(capacity()); position(24)
        listOf(20, 20, 0, 0, 1, 1, 80, 40, 120, 4, 0, 0x00cc0020, 40, 40).forEach(::putInt)
        putInt(40); putInt(1); putInt(1); putShort(1); putShort(24)
        position(120); put(0); put(0); put(255.toByte()); put(0)
    }.array()

    private fun buffer(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
}
