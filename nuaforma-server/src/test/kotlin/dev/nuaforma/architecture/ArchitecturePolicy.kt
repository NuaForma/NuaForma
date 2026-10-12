package dev.nuaforma.architecture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.*
import com.tngtech.archunit.lang.*
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import kotlin.reflect.KVisibility

/** Test-only policy, shared by real production checks and deliberately violating fixtures. */
internal class ArchitecturePolicy {
    data class Component(val module: String, val namespace: String, val allowed: Set<String>)
    val components = listOf(
        Component("nuaforma-contracts", "dev.nuaforma.contracts", emptySet()),
        Component("nuaforma-core", "dev.nuaforma.core", setOf("nuaforma-contracts")),
        Component("nuaforma-template", "dev.nuaforma.template", setOf("nuaforma-contracts")),
        Component("nuaforma-docx-pdf", "dev.nuaforma.docx.pdf", setOf("nuaforma-contracts", "nuaforma-docx", "nuaforma-template")),
        Component("nuaforma-docx", "dev.nuaforma.docx", setOf("nuaforma-contracts", "nuaforma-template")),
        Component("nuaforma-server", "dev.nuaforma.server", setOf("nuaforma-contracts", "nuaforma-core")),
    )

    // External types belong exclusively to their integration, never public facades.
    private val integrations = mapOf(
        "org.docx4j" to "dev.nuaforma.docx.adapters.docx4j",
        "jakarta.xml.bind" to "dev.nuaforma.docx.adapters.docx4j",
        "org.apache.pdfbox" to "dev.nuaforma.docx.pdf.adapters.pdfbox",
        "org.verapdf" to "dev.nuaforma.docx.pdf.adapters.verapdf",
        "io.ktor" to "dev.nuaforma.server.adapters.ktor",
        "kotlinx.coroutines" to "dev.nuaforma.server.adapters.ktor",
        "kotlinx.io" to "dev.nuaforma.server.adapters.ktor",
        "kotlinx.serialization" to "dev.nuaforma.server.adapters.json",
    )
    private val visibility = mutableMapOf<String, Boolean>()

    fun component(type: JavaClass): Component? = components.firstOrNull { inside(type.packageName, it.namespace) }

    fun rule(id: String, selected: Set<String>? = null): ArchRule {
        val condition = when (id) {
            "direction" -> dependencyCondition("follow component dependency direction") { source, target, _ ->
                val from = component(source)
                val to = component(target)
                to == null || (from != null && (from.module == to.module || to.module in from.allowed))
            }
            "internal-access" -> dependencyCondition("access only public APIs across component boundaries") { source, target, _ ->
                val from = component(source)
                val to = component(target)
                to == null || from?.module == to.module || exported(target)
            }
            "adapters" -> dependencyCondition("access external types only through their assigned adapter") { source, target, dependency ->
                if (component(target) != null || platform(target) || compilerAnnotation(target, dependency)) true
                else integrations.entries.any { (namespace, owner) -> inside(target.packageName, namespace) && inside(source.packageName, owner) }
            }
            "api-leaks" -> apiCondition()
            "cycles" -> cycleCondition()
            else -> error("Unknown architecture rule: $id")
        }
        val selectedClasses = object : DescribedPredicate<JavaClass>("selected production classes") {
            override fun test(input: JavaClass): Boolean = selected == null || input.name in selected
        }
        return classes().that(selectedClasses).should(condition).allowEmptyShould(false)
    }

    private fun dependencyCondition(
        description: String,
        permitted: (JavaClass, JavaClass, Dependency) -> Boolean,
    ) = object : ArchCondition<JavaClass>(description) {
        override fun check(item: JavaClass, events: ConditionEvents) {
            for (dependency in item.directDependenciesFromSelf) {
                val target = baseType(dependency.targetClass)
                if (!permitted(item, target, dependency)) events.add(SimpleConditionEvent.violated(item, dependency.description))
            }
        }
    }

    private fun apiCondition() = object : ArchCondition<JavaClass>("expose no external or implementation types through public APIs") {
        override fun check(item: JavaClass, events: ConditionEvents) {
            if (!exported(item)) return
            val signatures = mutableListOf<Pair<String, Set<JavaClass>>>()
            item.superclass.ifPresent { signatures += "${item.name} superclass" to it.allInvolvedRawTypes }
            item.interfaces.forEach { signatures += "${item.name} interface" to it.allInvolvedRawTypes }
            item.typeParameters.forEach { signatures += "${item.name} type bound" to it.allInvolvedRawTypes }
            item.fields.filter { visible(it.modifiers) }.forEach { signatures += it.fullName to it.type.allInvolvedRawTypes }
            (item.methods + item.constructors).filter { visible(it.modifiers) }.forEach {
                signatures += it.fullName to (it.allInvolvedRawTypes + it.exceptionTypes)
            }
            for ((location, types) in signatures) {
                for (raw in types) {
                    val type = baseType(raw)
                    if (!platform(type) && !exported(type)) {
                        events.add(SimpleConditionEvent.violated(item, "$location exposes ${type.name}"))
                    }
                }
            }
        }
    }

    private fun cycleCondition() = object : ArchCondition<JavaClass>("have no component dependency cycles") {
        private val edges = mutableMapOf<String, MutableSet<String>>()
        override fun check(item: JavaClass, events: ConditionEvents) {
            val from = component(item)?.module ?: return
            for (dependency in item.directDependenciesFromSelf) {
                val to = component(baseType(dependency.targetClass))?.module ?: continue
                if (from != to) edges.getOrPut(from) { linkedSetOf() }.add(to)
            }
        }
        override fun finish(events: ConditionEvents) {
            fun visit(node: String, path: List<String>) {
                if (node in path) {
                    events.add(SimpleConditionEvent.violated(node, "Component cycle: ${(path + node).joinToString(" -> ")}"))
                    return
                }
                edges[node].orEmpty().sorted().forEach { visit(it, path + node) }
            }
            edges.keys.sorted().forEach { visit(it, emptyList()) }
        }
    }

    private fun exported(type: JavaClass): Boolean {
        if (component(type) == null || !visible(type.modifiers)) return false
        if (type.enclosingClass.isPresent && !exported(type.enclosingClass.get())) return false
        if (type.packageName.split('.').any { it in setOf("internal", "adapters") } || inside(type.packageName, "dev.nuaforma.core.discovery")) return false
        return visibility.getOrPut(type.name) {
            if (!type.isAnnotatedWith(Metadata::class.java)) true
            else {
                val metadata = type.getAnnotationOfType(Metadata::class.java)
                // File facades, anonymous classes and synthetic holders are not component API entry points.
                metadata.kind == 1 && Class.forName(type.name, false, javaClass.classLoader).kotlin.visibility == KVisibility.PUBLIC
            }
        }
    }

    private fun compilerAnnotation(type: JavaClass, dependency: Dependency): Boolean =
        type.name in setOf("org.jetbrains.annotations.NotNull", "org.jetbrains.annotations.Nullable") &&
            dependency.description.contains("annotated with")

    private fun platform(type: JavaClass) = type.isPrimitive || inside(type.packageName, "java") || inside(type.packageName, "kotlin") ||
        inside(type.packageName, "javax.xml.stream") || type.name == "javax.xml.namespace.QName"
    private fun visible(modifiers: Set<JavaModifier>) = JavaModifier.PUBLIC in modifiers || JavaModifier.PROTECTED in modifiers
    private fun baseType(type: JavaClass): JavaClass = if (type.isArray) baseType(type.componentType) else type
    private fun inside(value: String, namespace: String) = value == namespace || value.startsWith("$namespace.")

    companion object {
        val RULES = listOf("direction", "internal-access", "adapters", "api-leaks", "cycles")
    }
}
