package dev.nuaforma.docx.adapters.rtf

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.internal.richtext.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Original, deliberately small RTF reader. No Office automation, host lookup or optional destinations. */
internal class BoundedRtfReader(private val source: String, private val budget: RichTextBudget) {
    private enum class Destination { BODY, FONT, COLOR, GENERATOR }
    private data class State(val style: RichStyle = RichStyle(), val uc: Int = 1, val page: Int = 1252,
                             val destination: Destination = Destination.BODY, val start: Boolean = true, val optional: Boolean = false)
    private data class Font(val family: String, val charset: Int)
    private class FontDraft(val id: Int) { var charset = 0; val name = StringBuilder() }
    private var state = State()
    private val stack = ArrayDeque<State>()
    private val fonts = mutableMapOf<Int, Font>()
    private val colors = mutableListOf<RichColor?>()
    private val destinations = mutableSetOf<Destination>()
    private var font: FontDraft? = null
    private var red = 0; private var green = 0; private var blue = 0; private var colorSet = false
    private var generatorLength = 0
    private var selectedCharset = 0
    private val charsetStack = ArrayDeque<Int>()
    private val paragraphs = mutableListOf<RichParagraph>()
    private val spans = mutableListOf<RichSpan>()
    private val text = StringBuilder()
    private var position = 0
    private var fallback = 0
    private var header = false
    private var ended = false
    private var tokens = 0
    private var runs = 0
    private var tokenPosition = 0
    private var highSurrogate: Int? = null

    fun read(): List<RichParagraph> {
        budget.checkActive()
        if (source.isEmpty()) return emptyList()
        if (source[0] != '{') invalid()
        while (position < source.length) {
            token()
            tokenPosition = position
            val character = source[position++]
            if (ended) { if (character !in " \r\n\t") invalid(); continue }
            when (character) {
                '{' -> openGroup()
                '}' -> closeGroup()
                '\\' -> control()
                '\r', '\n' -> Unit
                else -> literal(character)
            }
        }
        if (!ended || stack.isNotEmpty()) invalid()
        if (spans.isNotEmpty()) paragraph()
        budget.checkActive()
        return java.util.List.copyOf(paragraphs)
    }

    private fun openGroup() {
        fallback = 0
        if (stack.size >= budget.maxDepth) budget.limit()
        stack.addLast(state); charsetStack.addLast(selectedCharset)
        state = state.copy(start = true, optional = false)
    }
    private fun closeGroup() {
        fallback = 0
        if (stack.isEmpty() || !header || state.optional) invalid()
        val previous = stack.removeLast()
        if (state.destination == Destination.FONT && font != null) invalid()
        if (state.destination == Destination.COLOR && colorSet) invalid()
        if (state.destination != Destination.BODY) completeCharacter()
        if (state.destination == Destination.BODY && previous.style != state.style) flush()
        state = previous; selectedCharset = charsetStack.removeLast()
        if (stack.isEmpty()) { ended = true; flush() }
    }
    private fun literal(character: Char) {
        if (!header || stack.isEmpty() || state.optional) invalid()
        if (character.code !in 32..126) unsupported()
        state = state.copy(start = false)
        if (fallback > 0) fallback-- else append(character, raw = true)
    }

    private data class Control(val word: String, val number: Int?)

    private fun control() {
        if (stack.isEmpty() || position >= source.length) invalid()
        val first = source[position++]
        if (first == '\'') { hex(); return }
        if (!letter(first)) { symbol(first); return }
        val control = word()
        if (!header) { rootHeader(control); return }
        if (control.word !in WORDS && control.word !in SYMBOLS) unsupported()
        if (control.word in DESTINATION_WORDS) { destination(control); return }
        if (state.optional) unsupported()
        state = state.copy(start = false)
        if (fallback > 0) { fallback--; return }
        applyWord(control)
    }
    private fun letter(character: Char) = character in 'A'..'Z' || character in 'a'..'z'
    private fun symbol(first: Char) {
        if (!header) invalid()
        if (first == '*') {
            if (!state.start || state.optional) invalid()
            state = state.copy(optional = true); return
        }
        val value = when (first) { '\\', '{', '}' -> first; '~' -> '\u00a0'; '_' -> '\u2011'; else -> unsupported() }
        if (state.optional) unsupported()
        state = state.copy(start = false)
        if (fallback > 0) fallback-- else append(value)
    }
    private fun word(): Control {
        val start = position - 1
        while (position < source.length && letter(source[position])) {
            if (position - start >= 32) budget.limit()
            position++
        }
        val name = source.substring(start, position)
        val numberStart = position
        if (position < source.length && source[position] == '-') position++
        val digits = position
        while (position < source.length && source[position] in '0'..'9') {
            if (position - digits >= 9) budget.limit()
            position++
        }
        if (digits != numberStart && digits == position) invalid()
        val number = if (position > digits) source.substring(numberStart, position).toIntOrNull() ?: invalid() else null
        if (position < source.length && source[position] == ' ') position++
        return Control(name, number)
    }
    private fun rootHeader(control: Control) {
        if (control.word != "rtf" || control.number != 1 || stack.size != 1 || !state.start) invalid()
        header = true; state = state.copy(start = false)
    }
    private fun destination(control: Control) {
        if (control.number != null || stack.size != 2 || !state.start || state.destination != Destination.BODY ||
            paragraphs.isNotEmpty() || spans.isNotEmpty() || text.isNotEmpty()) unsupported()
        val destination = DESTINATION_WORDS.getValue(control.word)
        if (!destinations.add(destination)) invalid()
        state = state.copy(destination = destination, start = false, optional = false)
    }
    private fun applyWord(control: Control) {
        val (word, number) = control
        when (word) {
            in HEADER_WORDS -> headerWord(word, number)
            "uc", "u" -> unicode(word, number)
            in FONT_WORDS -> fontProperty(word, number)
            "f" -> font(required(number))
            in COLOR_WORDS -> colorProperty(word, number)
            else -> formatting(word, number)
        }
    }
    private fun headerWord(word: String, number: Int?) {
        bodyHeader()
        when (word) {
            "ansi", "nouicompat" -> absent(number)
            "ansicpg" -> { val page = required(number); charset(page); state = state.copy(page = page) }
            else -> if (required(number) !in 0..65535) unsupported()
        }
    }
    private fun unicode(word: String, number: Int?) {
        val value = required(number)
        if (word == "uc") {
            if (value !in 0..16) unsupported()
            state = state.copy(uc = value)
        } else {
            if (value !in -32768..32767) invalid()
            append((value and 65535).toChar()); fallback = state.uc
        }
    }
    private fun fontProperty(word: String, number: Int?) {
        if (state.destination != Destination.FONT) unsupported()
        val current = font ?: invalid()
        when (word) {
            "fcharset" -> { val value = required(number); fontPage(value); current.charset = value }
            "fprq" -> if (required(number) !in 0..2) unsupported()
            else -> absent(number)
        }
    }
    private fun colorProperty(word: String, number: Int?) {
        if (state.destination != Destination.COLOR) unsupported()
        val value = required(number); if (value !in 0..255) invalid()
        when (word) { "red" -> red = value; "green" -> green = value; else -> blue = value }
        colorSet = true
    }

    private fun formatting(word: String, number: Int?) {
        if (state.destination != Destination.BODY) unsupported()
        when (word) {
            "b" -> style(state.style.copy(bold = toggle(number)))
            "i" -> style(state.style.copy(italic = toggle(number)))
            "ul" -> style(state.style.copy(underline = toggle(number)))
            "ulnone" -> { absent(number); style(state.style.copy(underline = false)) }
            "strike" -> style(state.style.copy(strike = toggle(number)))
            "plain" -> { absent(number); style(RichStyle()); selectedCharset = 0 }
            "pard" -> absent(number) // Paragraph layout belongs to the template, not to this character-format fragment.
            "fs" -> { val size = required(number); if (size !in 2..400) unsupported(); style(state.style.copy(halfPoints = size)) }
            "cf" -> {
                val index = required(number)
                val color = if (index == 0 && colors.isEmpty()) null else {
                    if (index !in colors.indices) invalid()
                    colors[index]
                }
                style(state.style.copy(color = color))
            }
            "par" -> { absent(number); flush(); paragraph() }
            "tab" -> { absent(number); append('\t') }
            else -> { absent(number); append(SYMBOLS[word] ?: unsupported()) }
        }
    }
    private fun bodyHeader() {
        if (state.destination != Destination.BODY || stack.size != 1 || paragraphs.isNotEmpty() || spans.isNotEmpty() || text.isNotEmpty()) unsupported()
    }
    private fun font(id: Int) {
        if (id !in 0..65535) invalid()
        if (state.destination == Destination.FONT) {
            if (font != null || fonts.size >= 64) { if (fonts.size >= 64) budget.limit(); invalid() }
            font = FontDraft(id)
        } else {
            if (state.destination != Destination.BODY) unsupported()
            val selected = fonts[id] ?: invalid()
            style(state.style.copy(family = selected.family)); selectedCharset = selected.charset
        }
    }
    private fun append(character: Char, raw: Boolean = false) {
        checkCharacter(character)
        budget.character()
        when (state.destination) {
            Destination.BODY -> text.append(character)
            Destination.FONT -> fontCharacter(character, raw)
            Destination.COLOR -> colorCharacter(character, raw)
            Destination.GENERATOR -> { if (++generatorLength > 1024) budget.limit(); if (character.code < 32) invalid() }
        }
    }
    private fun checkCharacter(value: Char) {
        val pending = highSurrogate
        if (pending != null) {
            if (!value.isLowSurrogate()) fail(RejectionReason.INVALID_RICH_TEXT, pending)
            highSurrogate = null
        } else if (value.isHighSurrogate()) highSurrogate = tokenPosition
        else if (value.isLowSurrogate() || value != '\t' && value.code !in 32..0xFFFD) invalid()
    }
    private fun fontCharacter(character: Char, raw: Boolean) {
        val current = font ?: if (character == ' ') return else invalid()
        if (character == ';') {
            if (!raw) unsupported()
            val name = current.name.toString().trim(' ')
            if (name.isEmpty() || name.any { it.code < 32 } || fonts.containsKey(current.id)) invalid()
            valid(name); fonts[current.id] = Font(name, current.charset); font = null
        } else {
            if (current.name.length >= 128) budget.limit()
            current.name.append(character)
        }
    }
    private fun colorCharacter(character: Char, raw: Boolean) {
        if (character == ';') {
            if (!raw) unsupported()
            if (colors.size >= 256) budget.limit()
            colors.add(if (colorSet) RichColor(red, green, blue) else null)
            red = 0; green = 0; blue = 0; colorSet = false
        } else if (character != ' ') invalid()
    }
    private fun hex() {
        if (!header || state.optional) invalid()
        state = state.copy(start = false)
        val bytes = hexBytes()
        if (bytes.isEmpty()) return
        val active = if (state.destination == Destination.FONT) font?.charset ?: selectedCharset else selectedCharset
        val page = if (active == 0 || active == 1) state.page else fontPage(active)
        decode(bytes, page)
    }
    private fun hexBytes(): ByteArray {
        val bytes = ByteArrayOutputStream()
        do {
            if (position + 2 > source.length) invalid()
            val high = source[position++].digitToIntOrNull(16) ?: invalid()
            val low = source[position++].digitToIntOrNull(16) ?: invalid()
            if (fallback > 0) fallback-- else bytes.write(high * 16 + low)
            if (position + 1 >= source.length || source[position] != '\\' || source[position + 1] != '\'') break
            token(); position += 2
        } while (true)
        return bytes.toByteArray()
    }
    private fun decode(bytes: ByteArray, page: Int) {
        val decoder = charset(page).newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val input = ByteBuffer.wrap(bytes)
        val output = CharBuffer.allocate(1024)
        while (true) {
            budget.checkActive()
            val result = decoder.decode(input, output, true)
            if (result.isError) invalid()
            output.flip(); while (output.hasRemaining()) append(output.get()); output.clear()
            if (result.isUnderflow) break
        }
    }
    private fun style(value: RichStyle) { if (value != state.style) { flush(); state = state.copy(style = value) } }
    private fun flush() {
        completeCharacter()
        if (text.isEmpty()) return
        val value = text.toString(); valid(value)
        if (++runs > 4096) budget.limit()
        budget.run(); spans.add(RichSpan(value, state.style)); text.setLength(0)
    }
    private fun paragraph() {
        if (paragraphs.size >= 512) budget.limit()
        budget.paragraph(); paragraphs.add(RichParagraph(spans)); spans.clear()
    }
    private fun valid(value: String) {
        var index = 0
        while (index < value.length) {
            val point = value.codePointAt(index)
            if (point != 9 && point !in 32..0xD7FF && point !in 0xE000..0xFFFD && point !in 0x10000..0x10FFFF) invalid()
            index += Character.charCount(point)
        }
    }
    private fun token() { if (++tokens > 250_000) budget.limit(); budget.token() }
    private fun required(value: Int?): Int = value ?: invalid()
    private fun absent(value: Int?) { if (value != null) invalid() }
    private fun toggle(value: Int?): Boolean = when (value) { null, 1 -> true; 0 -> false; else -> invalid() }
    private fun charset(page: Int): Charset {
        if (page !in 1250..1258 && page != 65001) unsupported()
        return try { Charset.forName(if (page == 65001) "UTF-8" else "windows-$page") }
        catch (_: java.nio.charset.UnsupportedCharsetException) { unsupported() }
    }
    private fun fontPage(value: Int): Int = when (value) {
        0, 1 -> state.page; 162 -> 1254; 163 -> 1258; 177 -> 1255; 178 -> 1256; 186 -> 1257; 204 -> 1251; 238 -> 1250
        else -> unsupported()
    }
    private fun invalid(): Nothing = fail(RejectionReason.INVALID_RICH_TEXT)
    private fun unsupported(): Nothing = fail(RejectionReason.UNSUPPORTED_RICH_TEXT)
    private fun completeCharacter() { highSurrogate?.let { fail(RejectionReason.INVALID_RICH_TEXT, it) } }
    private fun fail(reason: RejectionReason, offset: Int = tokenPosition): Nothing = throw GenerationException(listOf(Diagnostic(
        DiagnosticCodes.INVALID_REQUEST, reason, location = "rtf:$offset")))

    companion object {
        private val SYMBOLS = mapOf("emdash" to '\u2014', "endash" to '\u2013', "bullet" to '\u2022',
            "lquote" to '\u2018', "rquote" to '\u2019', "ldblquote" to '\u201c', "rdblquote" to '\u201d')
        private val HEADER_WORDS = setOf("ansi", "ansicpg", "deff", "deflang", "deflangfe", "viewkind", "viewscale", "fet", "nouicompat")
        private val FONT_WORDS = setOf("fnil", "froman", "fswiss", "fmodern", "fscript", "fdecor", "ftech", "fbidi", "fcharset", "fprq")
        private val COLOR_WORDS = setOf("red", "green", "blue")
        private val DESTINATION_WORDS = mapOf("fonttbl" to Destination.FONT, "colortbl" to Destination.COLOR, "generator" to Destination.GENERATOR)
        private val WORDS = HEADER_WORDS + FONT_WORDS + COLOR_WORDS + DESTINATION_WORDS.keys +
            setOf("uc", "u", "f", "b", "i", "ul", "ulnone", "strike", "plain", "pard", "fs", "cf", "par", "tab")
    }
}
