package dev.nuaforma.template.internal

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.contracts.GenerationOptions
import dev.nuaforma.template.TemplateFragment
import dev.nuaforma.template.TemplateSession
import dev.nuaforma.template.ContentTemplateSession
import dev.nuaforma.template.ContentTemplateFragment
import dev.nuaforma.contracts.ContentPipe
import dev.nuaforma.template.TemplatePipes

internal class Session private constructor(
    private val state: Evaluation, private val scope: Map<String, Any?>, private val context: GenerationContext, private val depth: Int,
) : ContentTemplateSession {
    override fun expand(source: String): List<TemplateFragment> =
        program(source, false).expand(state, scope, depth)

    override fun expandContent(source: String): List<ContentTemplateFragment> =
        program(source).expandContent(state, scope, depth)

    override fun validateText(source: String) { program(source, false).validateDepth(state, depth) }

    override fun test(expression: String): Boolean = state.boolean(expression(expression).evaluate(state, scope))

    override fun branch(): ContentTemplateSession {
        context.checkActive()
        state.checkDepth(depth + 1)
        return Session(state, scope, context, depth + 1)
    }

    override fun repeat(expression: String, alias: String): List<ContentTemplateSession> {
        if (!Regex("[A-Za-z_][A-Za-z0-9_]*").matches(alias) || alias in setOf("data", "loop", "true", "false", "null")) Safety.invalid(0)
        state.checkDepth(depth + 1)
        return java.util.Collections.unmodifiableList(state.scopes(expression(expression).evaluate(state, scope), scope, alias)
            .map { Session(state, it, context, depth + 1) })
    }

    override fun validate(source: String) { program(source).validateDepth(state, depth) }
    override fun validateExpression(expression: String) { expression(expression) }

    private fun expression(source: String): Expression = Safety.inSource(source, context) { ExpressionParser(source, 0, context).parse() }.also { state.checkDepth(depth + it.depth - 1) }
    private fun program(source: String, content: Boolean = true): Program =
        TemplateParser(source, context, captureSpans = true, contentNames = if (content) state.contentNames else emptySet(), valuePipes = state.valuePipes).compile() as Program

    companion object {
        fun open(data: Map<String, *>, options: GenerationOptions, context: GenerationContext, pipes: TemplatePipes): TemplateSession {
            context.checkActive()
            return Session(Evaluation(Safety.snapshot(data, context), options, context, valuePipes = pipes), emptyMap(), context, 1)
        }
        fun openContent(data: Map<String, *>, pipes: List<ContentPipe>, options: GenerationOptions, context: GenerationContext, valuePipes: TemplatePipes): ContentTemplateSession {
            context.checkActive()
            return Session(Evaluation(Safety.snapshot(data, context), options, context, ContentRegistry(pipes), valuePipes), emptyMap(), context, 1)
        }
    }
}
