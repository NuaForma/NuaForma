package dev.nuaforma.template.internal

import dev.nuaforma.contracts.*
import java.io.ByteArrayInputStream
import java.net.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Collections
import java.util.ServiceLoader

/** Local classpath origin/compatibility validation, before any extension initialization. */
internal class ClasspathPipes(private val loader: ClassLoader) {
    fun discover(): List<TemplatePipe> {
        try {
            val services = resources(SERVICE)
            val metadata = mutableListOf<Pair<TemplatePipeMetadata, URI>>()
            for (service in services) {
                val expected = origin(service, SERVICE)
                val bytes = service.openConnection().apply { useCaches = false }.getInputStream().use { it.readNBytes(65537) }
                require(bytes.size <= 65536)
                val names = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().lineSequence()
                    .map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.distinct().toList()
                require(names.size <= 128)
                for (name in names) {
                    require(metadata.size < 128)
                    val path = TemplatePipeMetadata.resourcePath(name)
                    val descriptors = resources(path)
                    require(descriptors.size == 1 && origin(descriptors.single(), path) == expected)
                    val declaration = descriptors.single().openConnection().apply { useCaches = false }.getInputStream().use { TemplatePipeMetadata.read(it) }
                    require(declaration.implementationClass == name && ContractVersion.CURRENT.accepts(declaration.requiredContract))
                    val classPath = name.replace('.', '/') + ".class"
                    val classes = resources(classPath)
                    require(classes.size == 1 && origin(classes.single(), classPath) == expected)
                    metadata.add(declaration to expected)
                }
            }
            require(metadata.map { it.first.name }.distinct().size == metadata.size)
            require(metadata.map { it.first.implementationClass }.distinct().size == metadata.size)
            return metadata.sortedBy { it.first.name }.map { (declaration, expected) ->
                val pipe = instantiate(declaration.implementationClass, expected)
                val definition = pipe.definition
                require(definition.name == declaration.name)
                object : TemplatePipe {
                    override val definition = definition
                    override fun apply(value: Any?, arguments: List<Any?>, context: TemplatePipeContext): Any? = pipe.apply(value, arguments, context)
                }
            }
        } catch (_: Exception) { invalid() }
        catch (_: LinkageError) { invalid() }
        catch (_: java.util.ServiceConfigurationError) { invalid() }
    }

    private fun resources(path: String): List<URL> {
        val enumeration = loader.getResources(path)
        val result = linkedMapOf<String, URL>()
        var count = 0
        while (enumeration.hasMoreElements()) {
            require(++count <= 128)
            val resource = enumeration.nextElement()
            result[resource.toExternalForm()] = resource
        }
        return result.values.toList()
    }

    private fun instantiate(name: String, expected: URI): TemplatePipe {
        val bytes = "$name\n".toByteArray(StandardCharsets.UTF_8)
        val resource = URL(null, "nuaforma-service:validated", object : URLStreamHandler() {
            override fun openConnection(url: URL): URLConnection = object : URLConnection(url) {
                override fun connect() = Unit
                override fun getInputStream() = ByteArrayInputStream(bytes)
            }
        })
        val filtered = object : ClassLoader(null) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> = loader.loadClass(name)
            override fun getResources(name: String) = if (name == SERVICE) Collections.enumeration(listOf(resource)) else loader.getResources(name)
            override fun getResource(name: String): URL? = if (name == SERVICE) resource else loader.getResource(name)
        }
        val iterator = ServiceLoader.load(TemplatePipe::class.java, filtered).stream().iterator()
        require(iterator.hasNext())
        val entry = iterator.next()
        val type = entry.type()
        require(type.name == name && !type.module.isNamed)
        val source = requireNotNull(type.protectionDomain.codeSource?.location)
        require(source.protocol == "file" && Path.of(source.toURI()).toRealPath().toUri() == expected)
        require(!iterator.hasNext())
        return entry.get()
    }

    private fun origin(resource: URL, path: String): URI = when (resource.protocol) {
        "jar" -> {
            val connection = resource.openConnection() as JarURLConnection
            require(connection.jarFileURL.protocol == "file" && connection.entryName == path)
            Path.of(connection.jarFileURL.toURI()).toRealPath().toUri()
        }
        "file" -> {
            var root = Path.of(resource.toURI())
            for (part in path.split('/').asReversed()) {
                require(root.fileName.toString() == part)
                root = requireNotNull(root.parent)
            }
            root.toRealPath().toUri()
        }
        else -> throw IllegalArgumentException()
    }
    private fun invalid(): Nothing = Safety.fail(DiagnosticCodes.INVALID_REQUEST, RejectionReason.VALUE_PIPE_METADATA_COMPATIBILITY_ORIGIN_OR_REGISTRATION_IS_INVALID)
    companion object { private const val SERVICE = "META-INF/services/dev.nuaforma.contracts.TemplatePipe" }
}
