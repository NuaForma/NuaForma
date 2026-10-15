package dev.nuaforma.server.adapters.ktor

import dev.nuaforma.contracts.*
import dev.nuaforma.core.ProviderRegistry
import dev.nuaforma.server.ServerConfiguration
import dev.nuaforma.server.adapters.logging.OperationalLogging
import dev.nuaforma.server.adapters.json.KotlinxJsonCodec
import dev.nuaforma.server.internal.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.bodylimit.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import java.io.FilterOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.Semaphore
import java.time.Clock
import java.util.UUID
import io.ktor.util.AttributeKey

internal class KtorServer(private val registry: ProviderRegistry, private val config: ServerConfiguration,
                          private val logSink: GenerationLogSink = OperationalLogging) {
    private val json: JsonCodec = KotlinxJsonCodec()
    private val slots = Semaphore(config.maximumConcurrentRequests)
    private val documentation = ApiDocumentation(json)
    private val loggingKey = AttributeKey<GenerationLogging>("nuaforma.request.logging")
    private val health = ServiceHealth(registry, GenerationLogging(config.loggingMode, null, logSink))


    private fun logging(call: ApplicationCall): GenerationLogging = call.attributes[loggingKey]
    private suspend fun context(call: ApplicationCall): GenerationContext {
        val job = currentCoroutineContext().job
        return GenerationContext(config.generationLimits, CancellationToken { !job.isActive }, null, Clock.systemUTC(), logging(call))
    }

    fun startAndWait() {
        val server = embeddedServer(CIO, configure = {
            connector { host = config.host; port = config.port }
            shutdownGracePeriod = 1000
            shutdownTimeout = 5000
        }) { installRoutes(this) }
        val shutdown = Thread({ health.drain(); server.stop(1000, 5000) }, "nuaforma-shutdown")
        Runtime.getRuntime().addShutdownHook(shutdown)
        try { server.start(wait = true) }
        finally {
            health.close()
            try { Runtime.getRuntime().removeShutdownHook(shutdown) }
            catch (_: IllegalStateException) { /* JVM shutdown is already in progress. */ }
        }
    }

    fun installRoutes(application: Application) = with(application) {
        monitor.subscribe(ApplicationStarted) { health.start() }
        monitor.subscribe(ApplicationStopping) { health.drain() }
        monitor.subscribe(ApplicationStopped) { health.close() }
        intercept(ApplicationCallPipeline.Setup) {
            val identity = UUID.randomUUID().toString()
            val log = GenerationLogging(config.loggingMode, identity, logSink)
            call.attributes.put(loggingKey, log)
            call.response.headers.append("X-Request-ID", identity)
            val healthRequest = call.request.path() == ApiRoutes.LIVE || call.request.path() == ApiRoutes.READY
            withContext(RequestLogging(log)) {
                if (!healthRequest) log.operation("REQUEST_STARTED")
                try { proceed() }
                finally { if (!healthRequest) log.operation("REQUEST_FINISHED") }
            }
        }
        install(StatusPages) {
            exception<PayloadTooLargeException> { call, _ -> failure(call, 413, "RESOURCE_LIMIT_EXCEEDED", "Request body exceeds its limit") }
            exception<BadRequestException> { call, _ -> failure(call, 400, "INVALID_REQUEST", "Malformed HTTP request") }
            exception<Exception> { call, error ->
                if (error is java.util.concurrent.CancellationException) throw error
                logging(call).failure("HTTP_PROCESSING_FAILED", error)
                failure(call, 500, "GENERATION_FAILED", "Request processing failed")
            }
            status(HttpStatusCode.NotFound, HttpStatusCode.MethodNotAllowed) { call, status -> failure(call, status.value, "INVALID_REQUEST", "Unknown API route or method") }
        }
        install(RequestBodyLimit) { bodyLimit { config.maximumRequestBytes } }
        routing {
            get(ApiRoutes.LIVE) { healthResponse(call, health.live()) }
            get(ApiRoutes.READY) { healthResponse(call, health.ready()) }
            get(ApiRoutes.VERSION) { guarded(call) {
                call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                call.response.headers.append("X-Content-Type-Options", "nosniff")
                call.respondText(json.encode(BuildInformation.response()), ContentType.Application.Json)
            } }
            get(ApiRoutes.OPENAPI_JSON) { guarded(call) { openApi(call, documentation.jsonText, "json", ContentType.Application.Json) } }
            get(ApiRoutes.OPENAPI_YAML) { guarded(call) { openApi(call, documentation.yamlText, "yaml", ContentType.parse("application/yaml")) } }
            get(ApiRoutes.CAPABILITIES) { guarded(call) { call.respondText(json.encode(capabilities(context(call))), ContentType.Application.Json) } }
            post(ApiRoutes.ASSESS) { guarded(call) {
                if (!call.request.contentType().match(ContentType.Application.Json)) throw ApiFailure(415, "UNSUPPORTED_MEDIA_TYPE", "Expected application/json")
                val bytes = receiveBytes(call.receiveChannel(), config.maximumJsonBytes)
                val request = json.decode(bytes)
                val context = context(call)
                val provider = runInterruptible(Dispatchers.IO) { registry.select(request.requirements, request.providerId, context) }
                call.respondText(json.encode(mapOf("status" to "SUPPORTED", "providerId" to provider.metadata.id)), ContentType.Application.Json)
            } }
            post(ApiRoutes.DOCUMENTS) { guarded(call) { generate(call) } }
        }
    }

    private suspend fun healthResponse(call: ApplicationCall, healthy: Boolean) {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.response.headers.append("X-Content-Type-Options", "nosniff")
        call.respondText(json.encode(mapOf("status" to if (healthy) "UP" else "DOWN")), ContentType.Application.Json,
            if (healthy) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable)
    }

    private suspend fun openApi(call: ApplicationCall, text: String, extension: String, type: ContentType) {
        call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"nuaforma-openapi.$extension\"")
        call.response.headers.append("X-Content-Type-Options", "nosniff")
        call.respondText(text, type)
    }

    private suspend fun guarded(call: ApplicationCall, action: suspend () -> Unit) {
        if (!slots.tryAcquire()) {
            call.response.headers.append(HttpHeaders.RetryAfter, "1")
            failure(call, 503, "SERVER_BUSY", "No request slot is available")
            return
        }
        try {
            if (call.request.headers[HttpHeaders.ContentEncoding]?.let { it != "identity" } == true)
                throw ApiFailure(415, "UNSUPPORTED_MEDIA_TYPE", "Compressed request bodies are not supported")
            withTimeout(config.requestTimeout.toMillis()) { action() }
        } catch (_: TimeoutCancellationException) {
            failure(call, 408, "REQUEST_TIMEOUT", "Request deadline was reached")
        } catch (error: java.util.concurrent.CancellationException) { throw error }
        catch (error: ApiFailure) { failure(call, error.status, error.code, error.safeMessage) }
        catch (error: GenerationException) {
            logging(call).operation("GENERATION_REJECTED", mapOf("codes" to error.diagnostics.take(32).joinToString(",") { it.code.id },
                "locations" to error.diagnostics.mapNotNull { diagnostic -> diagnostic.location?.takeIf { it.matches(Regex("template:[0-9]{1,8}")) } }.take(8).joinToString(",")))
            call.respondText(json.encode(mapOf("requestId" to logging(call).requestId, "diagnostics" to ApiErrors.diagnostics(error.diagnostics))), ContentType.Application.Json,
                HttpStatusCode.fromValue(ApiErrors.status(error.diagnostics)))
        } catch (_: PayloadTooLargeException) { failure(call, 413, "RESOURCE_LIMIT_EXCEEDED", "Request body exceeds its limit") }
        catch (_: BadRequestException) { failure(call, 400, "INVALID_REQUEST", "Malformed HTTP request") }
        catch (error: Exception) { logging(call).failure("HTTP_PROCESSING_FAILED", error); failure(call, 500, "GENERATION_FAILED", "Request processing failed") }
        catch (error: LinkageError) { logging(call).failure("HTTP_LINKAGE_FAILED", error); failure(call, 500, "GENERATION_FAILED", "Request processing failed") }
        catch (error: java.util.ServiceConfigurationError) { logging(call).failure("HTTP_SERVICE_FAILED", error); failure(call, 500, "GENERATION_FAILED", "Request processing failed") }
        finally { slots.release() }
    }

    private suspend fun failure(call: ApplicationCall, status: Int, code: String, message: String) {
        logging(call).operation("REQUEST_REJECTED", mapOf("code" to code, "status" to status.toString()))
        if (!call.response.isCommitted) call.respondText(json.encode(mapOf("requestId" to logging(call).requestId, "diagnostics" to listOf(
            mapOf("code" to code, "severity" to "ERROR", "message" to message),
        ))), ContentType.Application.Json, HttpStatusCode.fromValue(status))
    }

    private suspend fun generate(call: ApplicationCall) {
        if (!call.request.contentType().match(ContentType.MultiPart.FormData)) throw ApiFailure(415, "UNSUPPORTED_MEDIA_TYPE", "Expected multipart/form-data")
        val directory = createPrivateDirectory()
        try {
            val parts = linkedMapOf<String, Path>()
            val types = linkedMapOf<String, ContentType?>()
            val multipart = call.receiveMultipart(formFieldLimit = config.maximumRequestBytes)
            var count = 0
            multipart.forEachPart { part ->
                try {
                    if (++count > config.maximumResources + 2) throw ApiFailure(413, "RESOURCE_LIMIT_EXCEEDED", "Too many multipart parts")
                    val name = part.name ?: throw ApiFailure(400, "INVALID_REQUEST", "Multipart parts require a name")
                    if (name != "request" && name != "template" && !Regex("resource\\.[A-Za-z0-9][A-Za-z0-9._-]{0,127}").matches(name))
                        throw ApiFailure(400, "INVALID_REQUEST", "Unknown multipart part")
                    if (name in parts) throw ApiFailure(400, "INVALID_REQUEST", "Duplicate multipart part")
                    val limit = when (name) { "request" -> config.maximumJsonBytes; "template" -> config.generationLimits.maxTemplateBytes; else -> config.maximumResourceBytes }
                    val type = part.contentType
                    if (name == "request" && (type == null || !type.match(ContentType.Application.Json))) throw ApiFailure(415, "UNSUPPORTED_MEDIA_TYPE", "Request part requires application/json")
                    if (name != "request" && part !is PartData.FileItem) throw ApiFailure(400, "INVALID_REQUEST", "Document and resource parts must be files")
                    val target = directory.resolve("part-${parts.size}")
                    when (part) {
                        is PartData.FormItem -> {
                            val bytes = part.value.toByteArray(Charsets.UTF_8)
                            if (bytes.size > limit) throw ApiFailure(413, "RESOURCE_LIMIT_EXCEEDED", "Multipart part exceeds its limit")
                            Files.write(target, bytes)
                        }
                        is PartData.FileItem -> Files.newOutputStream(target).use { sink -> copy(part.provider(), sink, limit) }
                        else -> throw ApiFailure(400, "INVALID_REQUEST", "Unsupported multipart part")
                    }
                    parts[name] = target
                    types[name] = type
                } finally { part.release() }
            }
            val requestPath = parts["request"] ?: throw ApiFailure(400, "INVALID_REQUEST", "Missing request part")
            val template = parts["template"] ?: throw ApiFailure(400, "INVALID_REQUEST", "Missing template part")
            val request = json.decode(Files.readAllBytes(requestPath))
            val templateType = types["template"]
            if (request.requirements.input.format == DocumentFormat.DOCX && templateType?.let {
                it.match(ContentType.Application.OctetStream) || it.match(ContentType.parse("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
            } != true) throw ApiFailure(415, "UNSUPPORTED_MEDIA_TYPE", "DOCX template requires its document media type or application/octet-stream")
            val resources = parts.filterKeys { it.startsWith("resource.") }.mapKeys { it.key.removePrefix("resource.") }
                .mapValues { (_, path) -> ResourceSource { Files.newInputStream(path) } }
            val output = directory.resolve("result")
            val context = context(call)
            val result = runInterruptible(Dispatchers.IO) {
                Files.newInputStream(template).use { input -> Files.newOutputStream(output).use { sink ->
                    val bounded = object : FilterOutputStream(sink) {
                        var count = 0L
                        override fun write(value: Int) { checkLimit(1); out.write(value) }
                        override fun write(bytes: ByteArray, offset: Int, length: Int) { checkLimit(length); out.write(bytes, offset, length) }
                        fun checkLimit(length: Int) {
                            context.checkActive()
                            if (length > config.generationLimits.maxOutputBytes - count) throw GenerationException(listOf(
                                Diagnostic(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, "Output exceeds its limit")))
                            count += length
                        }
                    }
                    registry.generate(GenerationRequest(input, request.requirements, request.data, resources), bounded, context, request.providerId)
                } }
            }
            val format = result.output.format.id
            val media = when (format) { "docx" -> ContentType.parse("application/vnd.openxmlformats-officedocument.wordprocessingml.document"); "pdf" -> ContentType.Application.Pdf; else -> ContentType.Application.OctetStream }
            // Only validated tokens, never uploaded filenames or user-supplied media types.
            call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"${request.filename}.$format\"")
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            call.response.headers.append("X-Content-Type-Options", "nosniff")
            call.response.headers.append("NuaForma-Warning-Codes", result.warnings.map { it.code.id }.distinct().take(32).joinToString(","))
            val sent = CompletableDeferred<Unit>()
            call.respondOutputStream(media, contentLength = Files.size(output)) {
                try { Files.newInputStream(output).use { input -> input.copyTo(this) }; sent.complete(Unit) }
                catch (failure: Throwable) { sent.completeExceptionally(failure); throw failure }
            }
            sent.await()
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                Files.list(directory).use { paths -> paths.forEach { Files.deleteIfExists(it) } }
                Files.deleteIfExists(directory)
            }
        }
    }

    private fun createPrivateDirectory(): Path {
        val posix = Files.getFileStore(config.temporaryDirectory).supportsFileAttributeView("posix")
        return if (posix) Files.createTempDirectory(config.temporaryDirectory, "nuaforma-http-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        else Files.createTempDirectory(config.temporaryDirectory, "nuaforma-http-")
    }

    private suspend fun receiveBytes(channel: ByteReadChannel, limit: Long): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        copy(channel, output, limit)
        return output.toByteArray()
    }

    private suspend fun copy(channel: ByteReadChannel, sink: OutputStream, limit: Long) {
        val buffer = ByteArray(8192)
        var count = 0L
        while (true) {
            val length = channel.readAvailable(buffer, 0, buffer.size)
            if (length == -1) break
            if (length == 0) { yield(); continue }
            count += length
            if (count > limit) throw ApiFailure(413, "RESOURCE_LIMIT_EXCEEDED", "Multipart part exceeds its limit")
            sink.write(buffer, 0, length)
        }
        channel.closedCause?.let { throw it }
    }

    private suspend fun capabilities(context: GenerationContext): Map<String, Any?> = runInterruptible(Dispatchers.IO) {
        mapOf("contractVersion" to ContractVersion.CURRENT.toString(), "diagnostics" to ApiErrors.diagnostics(registry.diagnostics),
            "providers" to registry.providers.map { registered ->
                val declared = registered.capabilities
                val available = try { registry.availability(registered, context) }
                    catch (error: Exception) {
                        context.checkActive()
                        context.logging.failure("PROVIDER_READINESS_FAILED", error, mapOf("provider" to registered.metadata.id))
                        ProviderAvailability(emptySet(), emptySet(), listOf(Diagnostic(DiagnosticCodes.PROVIDER_UNAVAILABLE, "Provider readiness failed")))
                    }
                mapOf("id" to registered.metadata.id, "releaseVersion" to registered.metadata.releaseVersion,
                    "requiredContract" to registered.metadata.requiredContract.toString(),
                    "declared" to mapOf("readFormats" to declared.readFormats.map { it.id }.sorted(), "writeFormats" to declared.writeFormats.map { it.id }.sorted()),
                    "available" to mapOf("readFormats" to available.readFormats.map { it.id }.sorted(), "writeFormats" to available.writeFormats.map { it.id }.sorted()),
                    "inputOptions" to declared.inputOptions.mapKeys { it.key.id }.mapValues { definitions(it.value) },
                    "outputOptions" to declared.outputOptions.mapKeys { it.key.id }.mapValues { definitions(it.value) },
                    "generalOptions" to definitions(declared.generalOptions), "diagnostics" to ApiErrors.diagnostics(available.diagnostics)) +
                    mapOf("fonts" to fontCatalog(registered.provider, context)) +
                    PipeCatalog.describe(registered.provider, declared, available, context)
            })
    }

    private fun fontCatalog(provider: DocumentProvider, parent: GenerationContext): Map<String, Any?> {
        if (provider !is FontCatalogProvider) return mapOf("status" to "UNSUPPORTED", "entries" to emptyList<Any>())
        return try {
            val catalog = provider.fontCatalog(GenerationContext(GenerationLimits(maxDuration = java.time.Duration.ofSeconds(10)), parent.cancellation,
                parent.deadline, Clock.systemUTC(), parent.logging))
            mapOf("status" to "AVAILABLE", "entries" to catalog.fonts.map {
                mapOf("family" to it.family, "face" to it.face, "source" to it.origin.name.lowercase(), "resource" to it.resource)
            })
        } catch (error: Exception) { parent.checkActive(); parent.logging.failure("FONT_CATALOG_UNAVAILABLE", error); mapOf("status" to "UNAVAILABLE", "entries" to emptyList<Any>()) }
        catch (error: LinkageError) { parent.logging.failure("FONT_CATALOG_UNAVAILABLE", error); mapOf("status" to "UNAVAILABLE", "entries" to emptyList<Any>()) }
    }

    private fun definitions(values: List<OptionDefinition>): List<Map<String, Any?>> = values.map {
        mapOf("name" to it.name, "type" to it.type.name, "description" to it.description, "required" to it.required,
            "defaultValue" to it.defaultValue, "allowedValues" to it.allowedValues)
    }
}
