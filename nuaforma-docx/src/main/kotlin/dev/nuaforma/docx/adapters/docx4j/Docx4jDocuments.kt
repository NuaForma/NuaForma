package dev.nuaforma.docx.adapters.docx4j

import dev.nuaforma.contracts.DiagnosticCodes
import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.contracts.GenerationException
import dev.nuaforma.contracts.GenerationRequest
import dev.nuaforma.contracts.ContentPipe
import dev.nuaforma.docx.DocxDocument
import dev.nuaforma.docx.internal.BoundedStreams
import dev.nuaforma.docx.internal.DocxFailure
import dev.nuaforma.docx.internal.TemporaryPackage
import org.docx4j.openpackaging.exceptions.Docx4JException
import org.docx4j.openpackaging.packages.WordprocessingMLPackage
import org.docx4j.openpackaging.parts.JaxbXmlPart
import org.docx4j.XmlUtils
import jakarta.xml.bind.JAXBException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipFile
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamException

internal object Docx4jDocuments {
    fun generate(request: GenerationRequest, output: OutputStream, context: GenerationContext, pipes: List<ContentPipe> = emptyList()) {
        open(request.template, context) {
            WordFonts.apply(it, request, context)
            val watermark = WordTemplates(request.data, request.requirements.options, context, pipes).apply(it)
            WordWatermarks(context).apply(it, watermark)
        }
            .use { it.writeTo(output) }
    }

    fun open(input: InputStream, context: GenerationContext, transform: (WordprocessingMLPackage) -> Unit = {}): DocxDocument {
        context.checkActive()
        return TemporaryPackage.create(context).use { temporary ->
            val spool = temporary.path
            Files.newOutputStream(spool).use { BoundedStreams.copy(input, it, context.limits.maxTemplateBytes, context) }
            PackageValidation.validate(spool, context)
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
                                    @Suppress("UNCHECKED_CAST")
                                    (part as JaxbXmlPart<Any>).jaxbElement = XmlUtils.unwrap(unmarshaller.unmarshal(reader))
                                } finally { reader.close() }
                            }
                        }
                    }
                    if (document.mainDocumentPart.jaxbElement.body == null) {
                        DocxFailure.fail(DiagnosticCodes.INVALID_TEMPLATE, "DOCX main document needs a body")
                    }
                }
            } catch (_: Docx4JException) {
                DocxFailure.fail(DiagnosticCodes.INVALID_TEMPLATE, "DOCX cannot be read; repair the package in a document editor")
            } catch (_: ClassCastException) {
                DocxFailure.fail(DiagnosticCodes.UNSUPPORTED_INPUT, "Input must be a DOCX text document")
            } catch (_: JAXBException) {
                DocxFailure.fail(DiagnosticCodes.INVALID_TEMPLATE, "DOCX contains unsupported or invalid structured XML; repair or simplify the document")
            } catch (_: XMLStreamException) {
                DocxFailure.fail(DiagnosticCodes.INVALID_TEMPLATE, "DOCX structured XML cannot be read; repair the document")
            } catch (_: IllegalArgumentException) {
                DocxFailure.fail(DiagnosticCodes.INVALID_TEMPLATE, "DOCX contains an invalid structured value; repair the document")
            }
            context.checkActive()
            try { transform(model) }
            catch (error: GenerationException) { throw error }
            catch (_: JAXBException) { DocxFailure.fail(DiagnosticCodes.INVALID_TEMPLATE, "DOCX structured transformation cannot be completed safely") }
            catch (_: RuntimeException) { DocxFailure.fail(DiagnosticCodes.INVALID_TEMPLATE, "DOCX structure cannot be transformed safely; simplify the template") }
            context.checkActive()
            Document(model, context)
        }
    }

    private class Document(private var model: WordprocessingMLPackage?, private val context: GenerationContext) : DocxDocument {
        @Synchronized
        override fun writeTo(output: OutputStream) {
            context.checkActive()
            val current = model ?: DocxFailure.fail(DiagnosticCodes.INVALID_REQUEST, "DOCX document is closed; open a new document")
            TemporaryPackage.create(context).use { temporary ->
                val spool = temporary.path
                try {
                    Files.newOutputStream(spool).use { current.save(BoundedStreams.Output(it, context.limits.maxOutputBytes, context)) }
                } catch (error: Docx4JException) {
                    generateSequence<Throwable>(error) { it.cause }.filterIsInstance<GenerationException>().firstOrNull()?.let { throw it }
                    DocxFailure.fail(DiagnosticCodes.GENERATION_FAILED, "DOCX serialization failed; no output was published")
                }
                PackageValidation.validate(spool, context)
                Files.newInputStream(spool).use { BoundedStreams.copy(it, output, context.limits.maxOutputBytes, context) }
            }
        }

        @Synchronized
        override fun close() { model = null }
    }
}
