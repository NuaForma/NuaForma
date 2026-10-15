package example

import dev.nuaforma.contracts.*

/** Independent extension, compiled against contracts only. */
class SelectPipe : TemplatePipe {
    override val definition = TemplatePipeDefinition("select", "Select an object member",
        listOf(OptionDefinition("key", OptionType.STRING, "Member name", required = true)),
        "Null is returned unchanged")
    override fun apply(value: Any?, arguments: List<Any?>, context: TemplatePipeContext): Any? {
        context.checkActive()
        if (value == null) return null
        require(value is Map<*, *>)
        return value[arguments.single()]
    }
}
