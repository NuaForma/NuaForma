# Independent value pipe extension

This source-only Java-17-compatible example builds a Kotlin `SelectPipe` JAR against **contracts only**, including its service entry and contract-version descriptor. A separate consumer depends on the packaged extension and template library, discovers it through ServiceLoader and renders a typed object → object → number → formatted-string chain. It is not supported by the published alpha libraries/images; use artifacts staged from this source tree.

From the repository root, first stage the local libraries:

```sh
./gradlew stageLibraries
```

Then run the independent project with the repository URI and the exact source artifact version (see root `gradle.properties`):

```sh
./gradlew --project-dir examples/value-pipes --refresh-dependencies \
  -PnuaformaRepository=file:///absolute/path/to/NuaForma/build/library-repository \
  -PnuaformaVersion=0.2.0-alpha.1 :consumer:run
```

The candidate `0.2.0-alpha.1` is not published by building this checkout. The exclusive local repository ensures this example uses staged candidate artifacts. This pipe declares minimum contract `0.2`; historical alpha libraries do not contain the SPI. Both subprojects use Java 17. The consumer runs the extension as a JAR so the class/service/descriptor have one artifact origin, rather than mixing Gradle class and resource directories.

For a Java implementation, see the public [SPI guide](../../nuaforma-template/README.md#implementation-and-registration). The template test suite compiles independent Java-17 contracts-only extension JARs. Operators install the extension JAR and explicitly packaged non-conflicting runtime dependencies in the read-only extension mount, then restart; normal application users place it on their classpath or call `TemplatePipes.discover(extensionLoader)` explicitly. No request may upload or install executable extensions. Installed implementations have host-process permissions and must be thread-safe, bound their own work and cooperate with cancellation.
