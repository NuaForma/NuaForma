package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement

/** Passive bibliography only; the owning DOCX adapter has already performed strict JAXB binding. */
internal class WordBibliographyMetadata(private val context: GenerationContext) {
    private val bib = "http://schemas.openxmlformats.org/officeDocument/2006/bibliography"
    private val custom = "http://schemas.openxmlformats.org/officeDocument/2006/customXml"
    private val office = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private val rel = "http://schemas.openxmlformats.org/package/2006/relationships"
    private val fields = ("Sources Source AbbreviatedCaseNumber AlbumTitle Artist Author BookAuthor BookTitle BroadcastTitle Broadcaster " +
        "CaseNumber ChapterNumber City Comments Compiler Composer Conductor ConferenceName Corporate Counsel CountryRegion Court Day " +
        "DayAccessed Department Director Distributor Edition Editor First Guid Institution InternetSiteTitle Interviewee Interviewer Inventor " +
        "Issue JournalName LCID Last Medium Middle Month MonthAccessed NameList NumberVolumes Pages PatentNumber Performer PeriodicalTitle " +
        "Person ProducerName ProductionCompany PublicationTitle Publisher RecordingNumber RefOrder Reporter ShortTitle SourceType " +
        "StandardNumber StateProvince Station Tag Theater ThesisType Title Translator Type URL Version Volume Writer Year YearAccessed").split(' ').toSet()

    fun removeFromPrintModel(parts: Map<String, XmlElement>): Set<String> {
        val candidates = parts.keys.filter { it.startsWith("customXml/") }.toSet()
        val main = parts["word/_rels/document.xml.rels"] ?: return emptySet<String>().also { require(candidates.isEmpty(), "package") }
        val links = main.elements().filter { it.attribute("", "Type") == "$office/customXml" }
        if (candidates.isEmpty() && links.isEmpty()) return emptySet()
        require(main.name.namespaceURI == rel && main.name.localPart == "Relationships", "word/_rels/document.xml.rels")
        val ids = main.elements().map { it.attribute("", "Id") }
        require(ids.all { !it.isNullOrBlank() } && ids.toSet().size == ids.size, "word/_rels/document.xml.rels")
        val used = linkedSetOf<String>()
        for (link in links) {
            context.checkActive()
            relationship(link, "customXml", "word/_rels/document.xml.rels")
            val target = link.attribute("", "Target") ?: fail("word/_rels/document.xml.rels")
            require(target.matches(Regex("\\.\\./customXml/item[1-9][0-9]?\\.xml")), "word/_rels/document.xml.rels")
            val name = target.removePrefix("../")
            require(used.add(name), name)
            source(parts[name] ?: fail(name), name)
            val relationsName = "customXml/_rels/${name.substringAfterLast('/')}.rels"
            parts[relationsName]?.let { propertiesLinks ->
                used.add(relationsName)
                require(propertiesLinks.name.namespaceURI == rel && propertiesLinks.name.localPart == "Relationships" && propertiesLinks.attributes.isEmpty(), relationsName)
                val propertyLink = propertiesLinks.elements().singleOrNull() ?: fail(relationsName)
                relationship(propertyLink, "customXmlProps", relationsName)
                val propertyTarget = propertyLink.attribute("", "Target") ?: fail(relationsName)
                require(propertyTarget.matches(Regex("itemProps[1-9][0-9]?\\.xml")), relationsName)
                val propertyName = "customXml/$propertyTarget"
                require(used.add(propertyName), propertyName)
                properties(parts[propertyName] ?: fail(propertyName), propertyName)
            }
        }
        require(used == candidates, "package")
        main.children.removeAll(links.toSet())
        return used
    }

    private fun relationship(node: XmlElement, type: String, part: String) {
        require(node.name.namespaceURI == rel && node.name.localPart == "Relationship" && node.elements().isEmpty(), part)
        require(node.attributes.all { it.key.namespaceURI.isEmpty() && it.key.localPart in setOf("Id", "Type", "Target", "TargetMode") }, part)
        require(!node.attribute("", "Id").isNullOrBlank() && node.attribute("", "Type") == "$office/$type" && node.attribute("", "TargetMode") in setOf(null, "Internal"), part)
    }

    private fun source(root: XmlElement, part: String) {
        require(root.name.namespaceURI == bib && root.name.localPart == "Sources", part)
        require(root.elements().all { it.name.localPart == "Source" }, part)
        for (node in root.descendants()) {
            context.checkActive()
            require(node.name.namespaceURI == bib && node.name.localPart in fields, part)
            require(node.attributes.all { node === root && it.key.namespaceURI.isEmpty() && it.key.localPart in setOf("SelectedStyle", "StyleName", "URI", "Version") }, part)
        }
    }

    private fun properties(root: XmlElement, part: String) {
        require(root.name.namespaceURI == custom && root.name.localPart == "datastoreItem", part)
        val identity = root.attribute(custom, "itemID") ?: fail(part)
        require(identity.matches(Regex("\\{[A-Fa-f0-9]{8}(?:-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12}\\}")), part)
        for (node in root.descendants()) {
            context.checkActive()
            require(node.name.namespaceURI == custom && node.name.localPart in setOf("datastoreItem", "schemaRefs", "schemaRef"), part)
            val attribute = if (node === root) "itemID" else if (node.name.localPart == "schemaRef") "uri" else null
            require(node.attributes.all { it.key.namespaceURI == custom && it.key.localPart == attribute }, part)
            if (node.name.localPart == "schemaRef") require(node.attribute(custom, "uri") == bib && node.elements().isEmpty(), part)
        }
        require(root.elements().size <= 1 && root.elements().all { it.name.localPart == "schemaRefs" && it.elements().all { child -> child.name.localPart == "schemaRef" } }, part)
    }

    private fun require(valid: Boolean, part: String) { if (!valid) fail(part) }
    private fun fail(part: String): Nothing = throw NativeDocxReadException("Unsupported or invalid bibliography metadata", part)
}
