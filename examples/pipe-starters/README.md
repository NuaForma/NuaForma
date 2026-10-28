# Value pipe starter: Maven or Gradle

Build one Java 17 extension JAR containing three stateless pipes: `prefix`, `uppercase` and `select`. It uses published NuaForma **0.3.0-alpha.2** from Maven Central, so no product checkout or private repository is needed. Maven and Gradle build the same sources; choose either, not both. The Gradle build uses Kotlin DSL; the pipe implementations are Java.

Prerequisites: JDK 17 and Maven 3.9.6+ for the Maven variant. The downloadable Gradle variant includes the product's pinned Gradle wrapper (Gradle itself requires JDK 17+). First builds download public build tools/dependencies. Nothing is published and no container is started.

```sh
# Maven variant
mvn verify
# JAR: target/nuaforma-pipe-starter-1.0.0.jar

# Gradle variant (download includes gradlew; in this checkout use ../../gradlew -p .)
./gradlew build
# Windows: gradlew.bat build
# JAR: build/libs/nuaforma-pipe-starter-1.0.0.jar
```

Tests discover all three pipes from the actual packaged JAR, render the examples, check nulls/types/arguments/limits/locale/cancellation, and run concurrent renders. Main sources compile against contracts only. NuaForma, Kotlin and test dependencies are not bundled in the JAR.

| Marker | Input | Result |
| --- | --- | --- |
| `{name \| prefix("Hello ")}` | `name: "Ada"` | `Hello Ada` |
| `{name \| uppercase()}` | `name: "Ada"` | `ADA` |
| `{customer \| select("name")}` | `customer: {name: "Ada"}` | `Ada` |
| `{customer \| select("name") \| uppercase() \| prefix("Customer: ")}` | `customer: {name: "Ada"}` | `Customer: ADA` |

Null stays null and inserts empty text. `select` deliberately returns null for an absent member. `uppercase` uses the generation locale, including Turkish casing rules. Strings/results are bounded to 4,096 UTF-16 units; selected keys to 256. Invalid types/arguments fail rather than being coerced.

Install only the built JAR in the container's host-side `extensions` directory (mounted read-only at `/opt/nuaforma/extensions`) and restart. Inspect capabilities or the Playground's pipe catalog, then use the markers in a Word template. Do not upload the JAR as a document resource. For embedded use add this JAR to the trusted application classpath alongside the matching template/DOCX libraries.

Change package/class names, pipe names and registration metadata together when adapting the examples. Keep the service entry and one descriptor per implementation in the same JAR. `contract.minor=2` is the SPI minimum, not the NuaForma release number. Do not install both variants or another extension with the same pipe names simultaneously. Extensions are trusted application code; keep them stateless, bounded and cooperatively cancellable.

The starter is Apache-2.0; retain the included LICENSE. Tests require no Word, LibreOffice, Docker or PDF validator. See https://nuaforma.dev/extensions.html for installation and the product's extension guide for the complete contract.
