package dev.nuaforma.core.discovery

import dev.nuaforma.contracts.*
import dev.nuaforma.core.ProviderRegistration
import dev.nuaforma.core.failure
import java.io.ByteArrayInputStream
import java.net.JarURLConnection
import java.net.URI
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Collections
import java.util.ServiceLoader
import java.util.function.Supplier

internal class ClasspathProviders(private val loader: ClassLoader) {
    internal class Scan(val registrations: List<ProviderRegistration>, val diagnostics: List<Diagnostic>)

    fun scan(): Scan {
        val registrations = mutableListOf<ProviderRegistration>()
        val diagnostics = mutableListOf<Diagnostic>()
        try {
            val services = Collections.list(loader.getResources(SERVICE)).distinctBy { it.toExternalForm() }
            require(services.size <= 1024)
            for (service in services) {
                val names: List<String>
                val serviceOrigin: URI
                try {
                    serviceOrigin = origin(service, SERVICE)
                    val bytes = service.openConnection().apply { useCaches = false }.getInputStream().use { it.readNBytes(65_537) }
                    require(bytes.size <= 65_536)
                    val decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                    names = decoder.decode(ByteBuffer.wrap(bytes)).toString().lineSequence()
                        .map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.distinct().toList()
                    require(names.size <= 1024)
                    names.forEach { ProviderMetadata.resourcePath(it) }
                } catch (error: Exception) {
                    diagnostics += failure("INVALID_PROVIDER_METADATA", "Invalid or unsupported local service descriptor")
                    continue
                }
                for (name in names) {
                    try {
                        val metadataPath = ProviderMetadata.resourcePath(name)
                        val descriptors = Collections.list(loader.getResources(metadataPath)).distinctBy { it.toExternalForm() }
                        require(descriptors.size == 1 && origin(descriptors.single(), metadataPath) == serviceOrigin)
                        val metadata = descriptors.single().openConnection().apply { useCaches = false }.getInputStream()
                            .use { ProviderMetadata.read(it) }
                        require(metadata.implementationClass == name)
                        val classPath = name.replace('.', '/') + ".class"
                        val classes = Collections.list(loader.getResources(classPath)).distinctBy { it.toExternalForm() }
                        require(classes.size == 1 && origin(classes.single(), classPath) == serviceOrigin)
                        registrations += ProviderRegistration(metadata, Supplier { instantiate(name, serviceOrigin) })
                    } catch (error: Exception) {
                        diagnostics += failure("INVALID_PROVIDER_METADATA", "Missing, malformed, duplicated or mismatched provider descriptor/class", name)
                    }
                }
            }
        } catch (error: Exception) {
            diagnostics += failure("INVALID_PROVIDER_METADATA", "Classpath service enumeration failed")
        }
        return Scan(registrations, diagnostics)
    }

    private fun instantiate(name: String, expectedOrigin: URI): DocumentProvider {
        val bytes = "$name\n".toByteArray(StandardCharsets.UTF_8)
        val resource = URL(null, "nuaforma-service:validated", object : URLStreamHandler() {
            override fun openConnection(url: URL): URLConnection = object : URLConnection(url) {
                override fun connect() = Unit
                override fun getInputStream() = ByteArrayInputStream(bytes)
            }
        })
        // Delegate class loading without a parent chain that would also discover unvalidated named-module services.
        val filtered = object : ClassLoader(null) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> = loader.loadClass(name)
            override fun getResources(name: String) = if (name == SERVICE) Collections.enumeration(listOf(resource)) else loader.getResources(name)
            override fun getResource(name: String): URL? = if (name == SERVICE) resource else loader.getResource(name)
        }
        val iterator = ServiceLoader.load(DocumentProvider::class.java, filtered).stream().iterator()
        require(iterator.hasNext())
        val entry = iterator.next()
        val type = entry.type()
        require(type.name == name && !type.module.isNamed)
        val source = requireNotNull(type.protectionDomain.codeSource?.location)
        require(source.protocol == "file" && Path.of(source.toURI()).toRealPath().toUri() == expectedOrigin)
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
        else -> throw IllegalArgumentException("Only local JARs and class directories are supported")
    }

    companion object {
        private const val SERVICE = "META-INF/services/dev.nuaforma.contracts.DocumentProvider"
    }
}
