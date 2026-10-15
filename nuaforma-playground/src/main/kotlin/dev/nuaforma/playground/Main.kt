package dev.nuaforma.playground

import dev.nuaforma.server.WebApplication

/** The production server has no dependency on this application or its assets. */
object Main {
    @JvmStatic fun main(args: Array<String>) = dev.nuaforma.server.Main.run(args, assets())

    fun assets(): WebApplication {
        val definitions = listOf("index.html" to "text/html", "app.css" to "text/css", "app.js" to "text/javascript", "icon.png" to "image/png")
        val inventory = requireNotNull(Main::class.java.getResourceAsStream("/META-INF/nuaforma/playground-sbom.json"))
            .use { it.readNBytes(16 * 1024 * 1024 + 1) }
        return WebApplication(definitions.map { (name, type) ->
            val bytes = requireNotNull(Main::class.java.getResourceAsStream("/playground/$name")) { "Missing Playground asset" }
                .use { it.readNBytes(1024 * 1024 + 1) }
            WebApplication.Asset(if (name == "index.html") "/playground/" else "/playground/$name", type, bytes)
        }, inventory)
    }
}
