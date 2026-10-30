package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.nativeengine.NativeDocxReadException
import dev.nuaforma.docx.pdf.adapters.nativeengine.WordBibliographyMetadata
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/** Admission-boundary cases that must be checked before metadata is excluded from print processing. */
class WordBibliographyGraphTest {
    private val bib = "http://schemas.openxmlformats.org/officeDocument/2006/bibliography"
    private val custom = "http://schemas.openxmlformats.org/officeDocument/2006/customXml"
    private val office = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private val relations = "http://schemas.openxmlformats.org/package/2006/relationships"
    private val main = "word/_rels/document.xml.rels"
    private val item = "customXml/item1.xml"
    private val itemRels = "customXml/_rels/item1.xml.rels"
    private val props = "customXml/itemProps1.xml"

    private fun graph() = linkedMapOf(
        main to """<Relationships xmlns="$relations"><Relationship Id="styles" Type="$office/styles" Target="styles.xml"/><Relationship Id="bibliography" Type="$office/customXml" Target="../customXml/item1.xml"/></Relationships>""",
        item to """<b:Sources xmlns:b="$bib" SelectedStyle="https://NOT_FETCHED/style.xsl" URI="file:///NOT_FETCHED" Version="6"><b:Source><b:Tag>PRIVATE_METADATA</b:Tag><b:Title>{not a template}</b:Title></b:Source></b:Sources>""",
        itemRels to """<Relationships xmlns="$relations"><Relationship Id="props" Type="$office/customXmlProps" Target="itemProps1.xml"/></Relationships>""",
        props to """<ds:datastoreItem xmlns:ds="$custom" ds:itemID="{A1E83116-3091-4B2E-94EA-CE8B1E3BF905}"><ds:schemaRefs><ds:schemaRef ds:uri="$bib"/></ds:schemaRefs></ds:datastoreItem>""",
    )

    private fun parse(graph: Map<String, String>, context: GenerationContext): Map<String, XmlElement> {
        val xml = ConversionXml(context)
        return graph.mapValues { xml.read(it.value.toByteArray()) }
    }

    @Test fun `identified passive metadata removes only its relationship and leaves other print links intact`() {
        val source = graph().apply {
            this[main] = getValue(main).replace("Id=\"bibliography\"", "Id=\"bibliography\" TargetMode=\"Internal\"")
            this[itemRels] = getValue(itemRels).replace("Id=\"props\"", "Id=\"props\" TargetMode=\"Internal\"")
        }
        val original = source.toMap()
        val context = GenerationContext()
        val parsed = parse(source, context)
        assertEquals(setOf(item, itemRels, props), WordBibliographyMetadata(context).removeFromPrintModel(parsed))
        assertEquals(listOf("styles"), parsed.getValue(main).elements().map { it.attribute("", "Id") })
        assertTrue(parsed.containsKey(item))
        assertEquals(original, source, "Only the print-model tree may change, not source parts")
    }

    @Test fun `self-contained sources need no optional datastore graph`() {
        val source = graph().apply { remove(itemRels); remove(props) }
        val context = GenerationContext()
        assertEquals(setOf(item), WordBibliographyMetadata(context).removeFromPrintModel(parse(source, context)))
    }

    @Test fun `documents without custom metadata need no relationship tree for this admission step`() {
        assertEquals(emptySet<String>(), WordBibliographyMetadata(GenerationContext()).removeFromPrintModel(emptyMap()))
        val context = GenerationContext()
        val source = graph().filterKeys { it == main }.mapValues { it.value.replace(Regex("<Relationship Id=\"bibliography\"[^>]*/>"), "") }
        assertEquals(emptySet<String>(), WordBibliographyMetadata(context).removeFromPrintModel(parse(source, context)))
    }

    @TestFactory fun `ambiguous foreign or incomplete metadata graphs cannot enter the print model`() =
        listOf<Pair<String, (MutableMap<String, String>) -> Unit>>(
            "metadata without a main relationship part" to { it.remove(main) },
            "blank main relationship identifier" to { it[main] = it.getValue(main).replace("Id=\"bibliography\"", "Id=\"\"") },
            "foreign relationship namespace" to { it[main] = it.getValue(main).replace(relations, "urn:foreign") },
            "wrong relationship root" to { it[main] = it.getValue(main).replace("Relationships", "Unexpected") },
            "missing source part" to { it.remove(item) },
            "external main relationship" to { it[main] = it.getValue(main).replace("Target=\"../customXml", "TargetMode=\"External\" Target=\"../customXml") },
            "traversal target" to { it[main] = it.getValue(main).replace("../customXml/item1.xml", "../customXml/../word/document.xml") },
            "main relationship without target" to { it[main] = it.getValue(main).replace(" Target=\"../customXml/item1.xml\"", "") },
            "repeated source with distinct identifiers" to { it[main] = it.getValue(main).replace("</Relationships>", "<Relationship Id=\"another\" Type=\"$office/customXml\" Target=\"../customXml/item1.xml\"/></Relationships>") },
            "foreign source attribute" to { it[item] = it.getValue(item).replace("Version=\"6\"", "Version=\"6\" unknown=\"PRIVATE_METADATA\"") },
            "foreign nested namespace" to { it[item] = it.getValue(item).replace("<b:Tag>", "<b:Tag xmlns:b=\"urn:foreign\">") },
            "unknown bibliography element" to { it[item] = it.getValue(item).replace("b:Title", "b:Unknown") },
            "attributes on bibliography values" to { it[item] = it.getValue(item).replace("<b:Title>", "<b:Title flag=\"x\">") },
            "wrong source root" to { it[item] = it.getValue(item).replace("b:Sources", "b:Source") },
            "non-source child at bibliography root" to { it[item] = it.getValue(item).replace("<b:Source>", "<b:Title>").replace("</b:Source>", "</b:Title>") },
            "empty properties relationship tree" to { it[itemRels] = "<Relationships xmlns=\"$relations\"/>" },
            "foreign properties relationship root" to { it[itemRels] = it.getValue(itemRels).replace(relations, "urn:foreign") },
            "extra relationship-root attributes" to { it[itemRels] = it.getValue(itemRels).replace("<Relationships ", "<Relationships flag=\"x\" ") },
            "relationship child payload" to { it[itemRels] = it.getValue(itemRels).replace("/></Relationships>", "><Payload/></Relationship></Relationships>") },
            "foreign relationship attribute" to { it[itemRels] = it.getValue(itemRels).replace("Id=\"props\"", "Id=\"props\" xmlns:x=\"urn:foreign\" x:flag=\"x\"") },
            "properties relationship missing target" to { it[itemRels] = it.getValue(itemRels).replace(" Target=\"itemProps1.xml\"", "") },
            "properties relationship traversal" to { it[itemRels] = it.getValue(itemRels).replace("itemProps1.xml", "../itemProps1.xml") },
            "properties target absent from package" to { it.remove(props) },
            "wrong datastore root" to { it[props] = it.getValue(props).replace("ds:datastoreItem", "ds:Unexpected") },
            "missing datastore identity" to { it[props] = it.getValue(props).replace(Regex(" ds:itemID=\"[^\"]+\""), "") },
            "malformed datastore identity" to { it[props] = it.getValue(props).replace("{A1E83116-3091-4B2E-94EA-CE8B1E3BF905}", "not-a-guid") },
            "foreign datastore attributes" to { it[props] = it.getValue(props).replace("<ds:schemaRefs>", "<ds:schemaRefs unknown=\"x\">") },
            "foreign datastore element" to { it[props] = it.getValue(props).replace("ds:schemaRefs", "ds:Unknown") },
            "non-leaf schema reference" to { it[props] = it.getValue(props).replace("/></ds:schemaRefs>", "><ds:schemaRefs/></ds:schemaRef></ds:schemaRefs>") },
            "multiple schema-reference containers" to { it[props] = it.getValue(props).replace("</ds:datastoreItem>", "<ds:schemaRefs/></ds:datastoreItem>") },
            "shared datastore properties" to {
                it["customXml/item2.xml"] = it.getValue(item)
                it["customXml/_rels/item2.xml.rels"] = it.getValue(itemRels)
                it[main] = it.getValue(main).replace("</Relationships>", "<Relationship Id=\"second\" Type=\"$office/customXml\" Target=\"../customXml/item2.xml\"/></Relationships>")
            },
        ).map { (name, mutate) -> dynamicTest(name) {
            val source = graph().apply(mutate)
            val context = GenerationContext()
            val error = assertThrows(NativeDocxReadException::class.java) {
                WordBibliographyMetadata(context).removeFromPrintModel(parse(source, context))
            }
            assertFalse(error.message.orEmpty().contains("PRIVATE_METADATA"))
        } }
}
