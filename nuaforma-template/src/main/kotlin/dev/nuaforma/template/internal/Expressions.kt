package dev.nuaforma.template.internal

import dev.nuaforma.contracts.DiagnosticCodes
import dev.nuaforma.contracts.GenerationContext
import java.math.BigDecimal

internal object Missing

internal sealed interface PipeStep {
    class Custom(val name: String, val arguments: List<Expression>) : PipeStep
    class Builtin(val format: Format) : PipeStep
}

internal sealed class Expression(val depth: Int) {
    abstract fun evaluate(state: Evaluation, scope: Map<String, Any?>): Any?

    class Literal(private val value: Any?) : Expression(1) {
        override fun evaluate(state: Evaluation, scope: Map<String, Any?>): Any? { state.step(); return value }
    }

    class Piped(private val input: Expression, private val pipes: List<PipeStep>) :
        Expression(maxOf(input.depth, pipes.filterIsInstance<PipeStep.Custom>().flatMap { it.arguments }.maxOfOrNull { it.depth } ?: 1) +
            pipes.count { it is PipeStep.Custom }) {
        override fun evaluate(state: Evaluation, scope: Map<String, Any?>): Any? {
            var value = input.evaluate(state, scope)
            for (pipe in pipes) {
                state.step()
                state.present(value)
                value = when (pipe) {
                    is PipeStep.Builtin -> pipe.format.apply(value, state)
                    is PipeStep.Custom -> state.pipe(pipe.name, value, pipe.arguments.map { it.evaluate(state, scope).also(state::present) })
                }
            }
            return value
        }
    }

    class Path(private val root: String, private val keys: List<Any>) : Expression(keys.size + 1) {
        override fun evaluate(state: Evaluation, scope: Map<String, Any?>): Any? {
            state.step()
            var value = when {
                root == "data" -> state.data
                scope.containsKey(root) -> scope[root]
                else -> state.data.getOrDefault(root, Missing)
            }
            for (key in keys) {
                state.step()
                value = when (value) {
                    null, Missing -> Missing
                    is Map<*, *> -> if (key is String) { if (value.containsKey(key)) value[key] else Missing } else state.typeFailure()
                    is List<*> -> if (key is Int) value.getOrElse(key) { Missing } else state.typeFailure()
                    else -> state.typeFailure()
                }
            }
            return value
        }
    }

    class Not(private val child: Expression) : Expression(child.depth + 1) {
        override fun evaluate(state: Evaluation, scope: Map<String, Any?>): Any? { state.step(); return !state.boolean(child.evaluate(state, scope)) }
    }

    class Call(private val operation: StringOperation, private val arguments: List<Expression>) :
        Expression(arguments.maxOf { it.depth } + 1) {
        override fun evaluate(state: Evaluation, scope: Map<String, Any?>): Any? {
            state.step()
            return operation.evaluate(arguments.map { it.evaluate(state, scope) }, state)
        }
    }

    class Binary(private val operator: String, private val left: Expression, private val right: Expression) :
        Expression(maxOf(left.depth, right.depth) + 1) {
        override fun evaluate(state: Evaluation, scope: Map<String, Any?>): Any? {
            state.step()
            val first = left.evaluate(state, scope)
            return when (operator) {
                "??" -> if (first == null || first === Missing) right.evaluate(state, scope) else first
                "&&" -> state.boolean(first) && state.boolean(right.evaluate(state, scope))
                "||" -> state.boolean(first) || state.boolean(right.evaluate(state, scope))
                else -> compare(state, first, right.evaluate(state, scope))
            }
        }

        private fun compare(state: Evaluation, left: Any?, right: Any?): Boolean {
            state.present(left); state.present(right)
            if (left is Map<*, *> || left is List<*> || right is Map<*, *> || right is List<*>) state.typeFailure()
            if (operator == "==" || operator == "!=") {
                val equal = when {
                    left == null || right == null -> left == right
                    left is BigDecimal && right is BigDecimal -> left.compareTo(right) == 0
                    left is String && right is String -> left == right
                    left is Boolean && right is Boolean -> left == right
                    else -> state.typeFailure()
                }
                return if (operator == "==") equal else !equal
            }
            val order = when {
                left is BigDecimal && right is BigDecimal -> left.compareTo(right)
                left is String && right is String -> left.compareTo(right)
                else -> state.typeFailure()
            }
            return when (operator) { "<" -> order < 0; "<=" -> order <= 0; ">" -> order > 0; ">=" -> order >= 0; else -> error("Unknown compiled operator") }
        }
    }
}

internal class ExpressionParser(private val text: String, private val offset: Int, private val context: GenerationContext) {
    private data class Token(val kind: String, val value: String, val position: Int)
    private val tokens = tokenize()
    private var index = 0
    private var nesting = 0
    private val token get() = tokens[index]

    fun parse(): Expression {
        val result = coalesce()
        if (token.kind != "end") invalid()
        return result
    }

    fun arguments(): List<Expression> {
        val result = mutableListOf<Expression>()
        if (token.kind != "end") {
            result.add(coalesce())
            while (take(",")) {
                if (result.size >= 16) Safety.limit()
                result.add(coalesce())
            }
        }
        if (token.kind != "end") invalid()
        return result.toList()
    }

    private fun coalesce(): Expression = nested {
        var result = or()
        if (take("??")) result = bounded(Expression.Binary("??", result, coalesce()))
        result
    }
    private fun or() = chain(::and, setOf("||"))
    private fun and() = chain(::equality, setOf("&&"))
    private fun equality() = chain(::ordering, setOf("==", "!="))
    private fun ordering() = chain(::unary, setOf("<", "<=", ">", ">="))

    private fun chain(next: () -> Expression, operators: Set<String>): Expression {
        var result = next()
        while (token.kind in operators) {
            val operator = token.kind
            index++
            result = bounded(Expression.Binary(operator, result, next()))
        }
        return result
    }

    private fun unary(): Expression = if (take("!")) nested { bounded(Expression.Not(unary())) } else atom()

    private fun atom(): Expression {
        context.checkActive()
        val start = token
        if (take("(")) {
            val result = coalesce()
            expect(")")
            return result
        }
        index++
        return when (start.kind) {
            "string" -> Expression.Literal(start.value)
            "number" -> Expression.Literal(number(start.value))
            "identifier" -> when (start.value) {
                "true" -> Expression.Literal(true)
                "false" -> Expression.Literal(false)
                "null" -> Expression.Literal(null)
                else -> {
                    if (take("(")) {
                        val operation = StringOperation.entries.firstOrNull { it.functionName == start.value }
                            ?: Safety.invalid(offset + start.position)
                        return nested {
                            val arguments = mutableListOf<Expression>()
                            if (token.kind != ")") {
                                arguments.add(coalesce())
                                while (take(",")) {
                                    if (arguments.size >= operation.maximumArguments) invalid()
                                    arguments.add(coalesce())
                                }
                            }
                            expect(")")
                            if (arguments.size !in operation.minimumArguments..operation.maximumArguments) invalid()
                            bounded(Expression.Call(operation, arguments.toList()))
                        }
                    }
                    val keys = mutableListOf<Any>()
                    while (token.kind in setOf(".", "[")) {
                        if (take(".")) {
                            if (token.kind != "identifier") invalid()
                            keys.add(token.value); index++
                        } else {
                            expect("[")
                            keys.add(when (token.kind) {
                                "string" -> token.value
                                "number" -> token.value.toIntOrNull()?.takeIf { it >= 0 } ?: invalid()
                                else -> invalid()
                            })
                            index++; expect("]")
                        }
                        if (keys.size >= Safety.depth(context)) Safety.limit()
                    }
                    bounded(Expression.Path(start.value, keys.toList()))
                }
            }
            else -> Safety.invalid(offset + start.position)
        }
    }

    private fun number(value: String): BigDecimal {
        val result = try { BigDecimal(value) } catch (_: NumberFormatException) { invalid() }
        if (result.precision() > 1000 || result.scale().toLong() !in -1000L..1000L) Safety.limit()
        return result
    }

    private fun bounded(expression: Expression): Expression {
        if (expression.depth > Safety.depth(context)) Safety.limit()
        return expression
    }
    private fun <T> nested(action: () -> T): T {
        if (++nesting > Safety.depth(context)) Safety.limit()
        return try { action() } finally { nesting-- }
    }
    private fun take(kind: String): Boolean = if (token.kind == kind) { index++; true } else false
    private fun expect(kind: String) { if (!take(kind)) invalid() }
    private fun invalid(): Nothing = Safety.invalid(offset + token.position)

    private fun tokenize(): List<Token> {
        if (text.length > 4096) Safety.limit()
        val result = mutableListOf<Token>()
        var cursor = 0
        fun invalid(): Nothing = Safety.invalid(offset + cursor)
        while (cursor < text.length) {
            context.checkActive()
            if (text[cursor].isWhitespace()) { cursor++; continue }
            if (result.size >= 1024) Safety.limit()
            val start = cursor
            val character = text[cursor]
            when {
                character == '"' -> {
                    cursor++
                    val value = StringBuilder()
                    var closed = false
                    while (cursor < text.length) {
                        val next = text[cursor++]
                        if (next == '"') { closed = true; break }
                        if (next == '\\') {
                            if (cursor >= text.length) invalid()
                            value.append(when (val escape = text[cursor++]) {
                                '"', '\\', '/' -> escape
                                'b' -> '\b'; 'f' -> '\u000c'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                                'u' -> {
                                    if (cursor + 4 > text.length) invalid()
                                    val code = text.substring(cursor, cursor + 4).toIntOrNull(16) ?: invalid()
                                    cursor += 4; code.toChar()
                                }
                                else -> invalid()
                            })
                        } else { if (next.code < 32) invalid(); value.append(next) }
                    }
                    if (!closed) invalid()
                    val literal = value.toString()
                    Safety.utf8Length(literal, context, DiagnosticCodes.INVALID_TEMPLATE)
                    result.add(Token("string", literal, start))
                }
                character in '0'..'9' || character == '-' -> {
                    cursor++
                    while (cursor < text.length && text[cursor] in "0123456789.eE+-") cursor++
                    val value = text.substring(start, cursor)
                    if (value.length > 256 || !NUMBER.matches(value)) invalid()
                    result.add(Token("number", value, start))
                }
                character in 'a'..'z' || character in 'A'..'Z' || character == '_' -> {
                    cursor++
                    while (cursor < text.length && (text[cursor] in 'a'..'z' || text[cursor] in 'A'..'Z' || text[cursor] in '0'..'9' || text[cursor] == '_')) cursor++
                    result.add(Token("identifier", text.substring(start, cursor), start))
                }
                else -> {
                    val pair = text.substring(cursor, minOf(cursor + 2, text.length))
                    val operator = if (pair in setOf("??", "&&", "||", "==", "!=", "<=", ">=")) pair else character.toString()
                    if (operator !in setOf("??", "&&", "||", "==", "!=", "<=", ">=", "!", "<", ">", "(", ")", "[", "]", ".", ",")) invalid()
                    cursor += operator.length
                    result.add(Token(operator, operator, start))
                }
            }
        }
        result.add(Token("end", "", text.length))
        return result
    }

    private companion object { val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?") }
}
