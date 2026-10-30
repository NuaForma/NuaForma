package dev.nuaforma.docx.adapters.docx4j

import dev.nuaforma.contracts.RejectionReason
import dev.nuaforma.contracts.DiagnosticCodes
import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.contracts.GenerationException
import dev.nuaforma.contracts.Diagnostic
import dev.nuaforma.contracts.GenerationRequest
import dev.nuaforma.contracts.ContentPipe
import dev.nuaforma.contracts.TextWatermark
import dev.nuaforma.template.TemplatePipes
import dev.nuaforma.docx.DocxDocument
import dev.nuaforma.docx.DeploymentFonts
import dev.nuaforma.docx.internal.BoundedStreams
import dev.nuaforma.docx.internal.DocxFailure
import dev.nuaforma.docx.internal.TemporaryPackage
import org.docx4j.openpackaging.exceptions.Docx4JException
import org.docx4j.openpackaging.packages.WordprocessingMLPackage
import org.docx4j.openpackaging.parts.JaxbXmlPart
import org.docx4j.XmlUtils
import jakarta.xml.bind.JAXBElement
import jakarta.xml.bind.annotation.XmlRootElement
import jakarta.xml.bind.JAXBException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipFile
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamException

internal object Docx4jDocuments {
    fun diagnosticCopy(request: GenerationRequest, output: OutputStream, context: GenerationContext, pipes: List<ContentPipe>, valuePipes: TemplatePipes, family: String): Diagnostic {
        if (context.logging.mode != dev.nuaforma.contracts.LoggingMode.DEVELOPMENT)
            DocxFailure.fail(DiagnosticCodes.UNSUPPORTED_OPTION, RejectionReason.TEMPLATE_DIAGNOSTICS_REQUIRE_DEVELOPMENT)
        var result: Diagnostic? = null
        open(request.template, context) { document ->
            val fault = WordTemplateFault(context)
            try {
                WordTemplates(request.data, request.requirements.options, context, pipes, valuePipes, fault).apply(document, true)
            } catch (error: GenerationException) {
                val diagnostic = error.diagnostics.firstOrNull { it.code == DiagnosticCodes.INVALID_TEMPLATE && it.location?.startsWith("template:") == true }
                    ?: throw error
                fault.annotate(document, diagnostic, family)
                omitHidden(document, context)
                result = diagnostic
            }
            if (result == null) DocxFailure.fail(DiagnosticCodes.INVALID_REQUEST, RejectionReason.TEMPLATE_DIAGNOSTIC_REQUIRES_PARSER_ERROR)
        }.use { it.writeTo(output) }
        return requireNotNull(result)
    }

    fun generate(request: GenerationRequest, output: OutputStream, context: GenerationContext, pipes: List<ContentPipe> = emptyList(), valuePipes: TemplatePipes = TemplatePipes.discover(), deploymentFonts: DeploymentFonts = DeploymentFonts.configured(), detachWatermark: Boolean = false): Pair<TextWatermark?, String?> {
        var decoration: Pair<TextWatermark?, String?> = null to null
        open(request.template, context) {
            if (request.requirements.output.options["fontSelection"] != "used") WordFonts.apply(it, request, context)
            val templates = WordTemplates(request.data, request.requirements.options, context, pipes, valuePipes)
            val watermark = templates.apply(it)
            if (templates.richTextUsed && request.requirements.output.options["fontSelection"] != "used")
                WordFonts.validateDeclaredFamilies(it, request, context)
            if (detachWatermark) omitHidden(it, context)
            decoration = watermark to watermark?.let { _ -> WordWatermarks.fontFamily(it) }
            if (!detachWatermark) WordWatermarks(context).apply(it, watermark)
            if (request.requirements.output.options["fontSelection"] == "used") AutomaticWordFonts(it, request, context, deploymentFonts).apply()
        }
            .use { it.writeTo(output) }
        return decoration
    }

    private fun omitHidden(document: WordprocessingMLPackage, context: GenerationContext) {
        val visibility = WordVisibility(document, context)
        document.parts.parts.values.filterIsInstance<JaxbXmlPart<*>>().forEach { part ->
            if (part is org.docx4j.openpackaging.parts.WordprocessingML.MainDocumentPart ||
                part is org.docx4j.openpackaging.parts.WordprocessingML.HeaderPart ||
                part is org.docx4j.openpackaging.parts.WordprocessingML.FooterPart ||
                part is org.docx4j.openpackaging.parts.WordprocessingML.FootnotesPart ||
                part is org.docx4j.openpackaging.parts.WordprocessingML.EndnotesPart) visibility.removeHidden(part.jaxbElement)
        }
    }

    fun open(input: InputStream, context: GenerationContext, transform: (WordprocessingMLPackage) -> Unit = {}): DocxDocument {
        context.checkActive()
        return TemporaryPackage.create(context).use { temporary ->
            val spool = temporary.path
            Files.newOutputStream(spool).use { BoundedStreams.copy(input, it, context.limits.maxTemplateBytes, context) }
            if (PackageValidation.validate(spool, context)) {
                DetachedTemplate.strip(spool, context)
                if (PackageValidation.validate(spool, context)) {
                    DocxFailure.fail(DiagnosticCodes.INVALID_TEMPLATE, RejectionReason.INVALID_ATTACHED_TEMPLATE_REFERENCE)
                }
            }
            val model = try {
                Files.newInputStream(spool).use { WordprocessingMLPackage.load(it) }.also { document ->
                    ZipFile(spool.toFile()).use { zip ->
                        document.parts.parts.values.filterIsInstance<JaxbXmlPart<*>>().forEach { part ->
                            context.checkActive()
                            val unmarshaller = part.jaxbContext.createUnmarshaller()
                            // Do not invoke docx4j's tolerant fallback or its content-bearing validation logger.
                            unmarshaller.setEventHandler { false }
                            zip.getInputStream(zip.getEntry(part.getPartName().name.removePrefix("/"))).use { source ->
                                val factory = XMLInputFactory.newDefaultFactory().apply {
                                    setProperty(XMLInputFactory.SUPPORT_DTD, false)
                                    setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
                                }
                                val reader = factory.createXMLStreamReader(source)
                                try {
                                    val root = unmarshaller.unmarshal(reader)
                                    val value = XmlUtils.unwrap(root)
                                    // Generated types without XmlRootElement need their QName wrapper when saved.
                                    val element = if (root is JAXBElement<*> && (value == null || !value.javaClass.isAnnotationPresent(XmlRootElement::class.java))) root else value
                                    @Suppress("UNCHECKED_CAST")
                                    (part as JaxbXmlPart<Any>).jaxbElement = element
                                } catch (_: JAXBException) {
                                    throw GenerationException(listOf(dev.nuaforma.contracts.Diagnostic(DiagnosticCodes.INVALID_TEMPLATE,
                                        RejectionReason.DOCX_CONTAINS_UNSUPPORTED_OR_INVALID_STRUCTURED_XML_REPAIR_OR,
                                        location = "xml:${reader.location.lineNumber.coerceAtLeast(0)}:${reader.location.columnNumber.coerceAtLeast(0)}",
                                        part = part.getPartName().name.removePrefix("/"))))
                                } finally { reader.close() }
                            }
                        }
                    }
                    if (document.mainDocumentPart.jaxbElement.body == null) {
                        DocxFailure.fail(DiagnosticCodes.INVALID_TEMPLATE, RejectionReason.DOCX_MAIN_DOCUMENT_NEEDS_A_BODY)
                    }
                }
            } catch (_: Docx4JException) {
                DocxFailure.fail(DiagnosticCodes.INVALID_TEMPLATE, RejectionReason.DOCX_CANNOT_BE_READ_REPAIR_THE_PACKAGE_IN_A)
            } catch (_: ClassCastException) {
                DocxFailure.fail(DiagnosticCodes.UNSUPPORTED_INPUT, RejectionReason.INPUT_MUST_BE_A_DOCX_TEXT_DOCUMENT)
            } catch (_: JAXBException) {
                DocxFailure.fail(DiagnosticCodes.INVALID_TEMPLATE, RejectionReason.DOCX_CONTAINS_UNSUPPORTED_OR_INVALID_STRUCTURED_XML_REPAIR_OR)
            } catch (_: XMLStreamException) {
                DocxFailure.fail(DiagnosticCodes.INVALID_TEMPLATE, RejectionReason.DOCX_STRUCTURED_XML_CANNOT_BE_READ_REPAIR_THE_DOCUMENT)
            } catch (_: IllegalArgumentException) {
                DocxFailure.fail(DiagnosticCodes.INVALID_TEMPLATE, RejectionReason.DOCX_CONTAINS_AN_INVALID_STRUCTURED_VALUE_REPAIR_THE_DOCUMENT)
            }
            context.checkActive()
            try { transform(model) }
            catch (error: GenerationException) { throw error }
            catch (_: JAXBException) { DocxFailure.fail(DiagnosticCodes.INVALID_TEMPLATE, RejectionReason.DOCX_STRUCTURED_TRANSFORMATION_CANNOT_BE_COMPLETED_SAFELY) }
            catch (error: RuntimeException) {
                context.logging.failure("DOCX_TRANSFORMATION_FAILED", error)
                DocxFailure.fail(DiagnosticCodes.INVALID_TEMPLATE, RejectionReason.DOCX_STRUCTURE_CANNOT_BE_TRANSFORMED_SAFELY_SIMPLIFY_THE_TEMPLATE)
            }
            context.checkActive()
            Document(model, context)
        }
    }

    private class Document(private var model: WordprocessingMLPackage?, private val context: GenerationContext) : DocxDocument {
        @Synchronized
        override fun writeTo(output: OutputStream) {
            context.checkActive()
            val current = model ?: DocxFailure.fail(DiagnosticCodes.INVALID_REQUEST, RejectionReason.DOCX_DOCUMENT_IS_CLOSED_OPEN_A_NEW_DOCUMENT)
            TemporaryPackage.create(context).use { temporary ->
                val spool = temporary.path
                try {
                    Files.newOutputStream(spool).use { current.save(BoundedStreams.Output(it, context.limits.maxOutputBytes, context)) }
                } catch (error: Docx4JException) {
                    generateSequence<Throwable>(error) { it.cause }.filterIsInstance<GenerationException>().firstOrNull()?.let { throw it }
                    context.logging.failure("DOCX_SERIALIZATION_FAILED", error)
                    DocxFailure.fail(DiagnosticCodes.GENERATION_FAILED, RejectionReason.DOCX_SERIALIZATION_FAILED_NO_OUTPUT_WAS_PUBLISHED)
                }
                PackageValidation.validate(spool, context)
                Files.newInputStream(spool).use { BoundedStreams.copy(it, output, context.limits.maxOutputBytes, context) }
            }
        }

        @Synchronized
        override fun close() { model = null }
    }
}
