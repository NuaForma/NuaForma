package dev.nuaforma.docx.adapters.docx4j

import dev.nuaforma.contracts.*
import org.docx4j.XmlUtils
import org.docx4j.openpackaging.packages.WordprocessingMLPackage
import org.docx4j.wml.*

internal object WordDiagnosticDocument {
    fun annotate(document: WordprocessingMLPackage, diagnostic: Diagnostic, context: GenerationContext) {
        val body = document.mainDocumentPart.jaxbElement.body
        val part = (if (diagnostic.part == "word/document.xml") document.mainDocumentPart else document.parts.parts.values.firstOrNull { it.getPartName().name.removePrefix("/") == diagnostic.part })
            as? org.docx4j.openpackaging.parts.JaxbXmlPart<*>
        val index = diagnostic.documentLocation?.removePrefix("paragraph:")?.toIntOrNull()
        var number = 0
        fun walk(raw: Any, depth: Int) {
            context.checkActive()
            if (depth > 128) return
            val node = XmlUtils.unwrap(raw)
            if (node is P && ++number == index) node.content.add(0, label("[ERROR 1] "))
            WordVisibility.children(node).toList().forEach { walk(it, depth + 1) }
        }
        if (part != null && index != null) walk(part.jaxbElement, 0)
        fun line(value: String) { body.content.add(P().apply { content.add(label(value)) }) }
        line("DIAGNOSTIC COPY - NOT A GENERATED DOCUMENT")
        line("Reason: ${diagnostic.rejectionReason?.name ?: diagnostic.code.id}")
        line("Part: ${diagnostic.part ?: "unavailable"}; ${diagnostic.documentLocation ?: "No paragraph coordinate: inspect the part/path below."}")
        diagnostic.sourceContext?.let { source ->
            line(source.explanation)
            line("XML path: ${source.path}")
            line("Source excerpt: ${source.excerpt}")
        }
        diagnostic.templateContext?.let { line("Template fragment: ${it.expression}") }
    }

    fun label(value: String): R = R().apply {
        rPr = RPr().apply {
            color = Color().apply { `val` = "C00000" }
            vanish = BooleanDefaultTrue().apply { isVal = false }
        }
        content.add(ObjectFactory().createRT(Text().apply { this.value = value; space = "preserve" }))
    }
}
