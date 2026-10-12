# Third-party dependency notices

NuaForma is Apache-2.0 licensed. Dependencies keep their own licenses; NuaForma does not relicense them. Standard library JARs refer to dependencies through Maven metadata and do not shade, copy or modify their classes. Consumers distributing a combined application must retain the licenses, copyright notices and applicable NOTICE files of the actual dependency artifacts, including their bundled subcomponents.

## DOCX integration

The `nuaforma-docx` runtime uses docx4j 17.3.2, with its reference JAXB implementation. docx4j is maintained by Plutext Pty Ltd. Its [license](https://github.com/plutext/docx4j/blob/VERSION_17_3_2/legals/LICENSE) is Apache-2.0; its [NOTICE](https://github.com/plutext/docx4j/blob/VERSION_17_3_2/legals/NOTICE) lists bundled sources with additional terms, including BSD, MIT, Microsoft Public License and W3C-related material. Preserve that upstream NOTICE and those licenses when redistributing the unmodified docx4j artifacts. Do not describe the complete dependency stack as exclusively Apache-2.0.

Runtime coordinates and exact resolved versions are recorded in the module lockfiles. The reviewed licensing groups are:

| Runtime components | License family |
| --- | --- |
| docx4j core, generated objects, copy, JAXB selector; JAXB SVG bindings; relocated Xalan; Apache Commons, FontBox/PDFBox IO, XML Graphics; QDox; SparseBitSet; Error Prone annotations; Kotlin and JetBrains annotations | Apache-2.0, with upstream subcomponent notices where applicable |
| JAXB API, JAXB runtime/core/txw2, Jakarta Activation, Angus Activation and istack runtime | Eclipse Distribution License 1.0 / BSD-style terms |
| ANTLR 2; ANTLR 3, StringTemplate and JAXB plugins runtime | ANTLR 2 public-domain/permissive terms plus BSD-3-Clause Python-generator code; BSD-style terms for the others |
| MBassador, Checker Framework qualifiers and SLF4J API | MIT |
| SLF4J Commons Logging bridge | Apache-2.0 |

Licenses were checked against the pinned Maven POMs (including parent inheritance) and upstream artifact license material. For JAXB, see the [reference implementation license](https://github.com/eclipse-ee4j/jaxb-ri/blob/master/LICENSE.md) and [API license](https://github.com/jakartaee/jaxb-api/blob/main/LICENSE.md). EDL is a permissive distribution license, distinct from EPL. Preserve the actual artifact notices rather than using this summary as replacement license text.

The service container supplements artifact-level metadata with original dependency source/notice materials, including source-file copyright headers, docx4j's version-specific LICENSE/NOTICE and ANTLR 2's separate terms. Several upstream binary JARs contain no licence text; retaining those JARs unchanged alone does not fulfil all attribution duties. The unmodified material under `/opt/nuaforma/application/third-party-materials` is outside the runtime classpath. ANTLR 2's unrelated Python runtime is not included in the application binary JAR; the retained upstream source archive preserves its original licensing distinction. See [ANTLR 2's license statement](https://www.antlr2.org/license.html) and the release's [source-access instructions](deployment/pdf-runtime/SOURCES.md).

DOCX-only consumers acquire no PDF conversion backend, Office automation dependency, server framework, log implementation or paid docx4j component. Local Word rendering of synthetic fixtures is an optional fidelity check only and does not form part of NuaForma's runtime or distributable dependencies.

## REST integration

The server uses [Ktor 3.6.0](https://github.com/ktorio/ktor/blob/3.6.0/LICENSE), Kotlinx Coroutines 1.11.0, Kotlinx Serialization 1.11.0 and Kotlinx IO 0.9.1 under Apache-2.0, plus Apache-2.0 Typesafe Config 1.4.9 and MIT SLF4J API 2.0.20. They remain separate artifacts, not shaded classes or dependencies of the embedded engine libraries. Preserve their original license/copyright and any applicable notice material in a redistributed service. Ktor's test host and its additional clients, test/debug and logging artifacts are test-only and not part of the production service distribution. A locally built application archive is not a publicly audited or published combined runtime.

## OpenAPI test tooling

[Swagger Parser 2.1.48](https://github.com/swagger-api/swagger-parser/blob/v2.1.48/LICENSE) and its Swagger Core 2.2.55, Jackson 2.22/2.22.3, SnakeYAML 2.7 and Jakarta Validation API 2.0.2 transitives are test-only Apache-2.0 components. Production code uses no Swagger/YAML parser/vendor model, and the service/library distributions do not contain these artifacts. Preserve their original LICENSE/NOTICE and copyright material if distributing the tools independently; NuaForma's license is not a substitute. The existing JAXB/SLF4J/Commons transitive terms remain unchanged. See the dependency review for the deliberately patched Jackson test alignment and advisory snapshot.

The upgraded MockK/Ktor test graph includes Apache-2.0 Byte Buddy/agent 1.18.14, Objenesis 3.6, JSpecify 1.0.1 and Apache HTTP components 5.6.5/5.4.4. JNA/platform 5.19.1 remain test-only; use the explicit Apache-2.0 alternative of their [Apache-2.0 OR LGPL-2.1-or-later license](https://github.com/java-native-access/jna/blob/5.19.1/LICENSE), retaining original artifact/native subcomponent notices when redistributing test tooling. These tools are not added to service/library distributions.

## Barcode verification assets and tools

DOCX tests include the unmodified Libre Barcode 39 font under SIL Open Font License 1.1, with its upstream copyright and license in `nuaforma-docx/src/test/resources/fonts/OFL.txt`. The adjacent README records its pinned source and checksum. It is not included in production JARs or installed by NuaForma. Font licenses remain independent of the engine license; callers are responsible for their font usage rights. The engine follows technical OpenType embedding permissions without requiring license evidence or an additional consent declaration.

Optional local scan verification uses ZXing-C++ 3.1.1, Apache-2.0 licensed, through its Python wheel with no additional runtime requirements declared. This tool is not a Gradle, CI or production dependency. The reviewed [upstream license](https://github.com/zxing-cpp/zxing-cpp/blob/v3.1.1/LICENSE) and [release](https://github.com/zxing-cpp/zxing-cpp/releases/tag/v3.1.1) belong to that tool, not to the font. The independently published wheel is checksum-verified before local installation into ignored QA storage.

## Approved PDF renderer direction

LibreOffice is bundled in the locally built [runtime qualification/service images](deployment/pdf-runtime/README.md), not in library JARs or a published image. Its [upstream licensing](https://www.libreoffice.org/licenses/) includes MPL-2.0 and version-specific third-party terms. This guide does not replace the actual installation's LICENSE, NOTICE, copyright material or source distribution. Follow the [distribution checklist](docs/libreoffice-licensing.md) before redistributing a renderer/runtime, and include an exact-package notice inventory and matching source provision with that distribution. A NuaForma Apache-2.0 LICENSE alone is not sufficient for a combined runtime. Locally available proprietary fonts used for private interoperability checks must not be copied into a redistributable image.

Bubblewrap 0.13.0 is an unmodified separately executed LGPL-2.1-or-later helper. The qualification image retains its COPYING/COPYING.LIB and the exact source archive with build instructions in Dockerfile. The adapted Moby seccomp profile is Apache-2.0; its upstream attribution, commit, original checksum and changes are recorded in `deployment/pdf-runtime/NOTICE`, with Apache-2.0 text included in the image. Debian/Temurin materials and bundled font notices remain independent requirements; no combined image is publicly distributed before its release-specific audit.

The optional PDF module adds Apache PDFBox 3.0.8 for structural output validation in an isolated child JVM. Preserve the original [LICENSE](https://github.com/apache/pdfbox/blob/3.0.8/LICENSE.txt) and [NOTICE](https://github.com/apache/pdfbox/blob/3.0.8/NOTICE.txt), including bundled additional terms. FontBox/IO were already locked through docx4j; Commons Logging 1.4.0 is an additional Apache-2.0 transitive dependency whose artifact notices must also be retained. The separate validator classpath is staged without DOCX, core, server or test libraries. No PDFBox classes are shaded into NuaForma library artifacts.

## PDF/A validation runtime

The production archival validator uses unmodified veraPDF 1.30.3 library JARs under the approved MPL-2.0 choice, in a separately isolated child JVM. Retain the original MPL/GPL notices rather than relabeling vendor files as Apache-2.0. Its validation profiles have separate CC-BY-4.0 terms and consortium attribution; the retained [profile attribution](deployment/pdf-runtime/licenses/verapdf/PROFILES-ATTRIBUTION.md) is upstream material, not a claim that NuaForma supports every profile it mentions.

The locked `veraPdfRuntime` configuration is staged separately from the host application and Maven library publications, with the unused legacy StAX report utility excluded. Original Saxon/Rhino/JAXB and other transitive component licenses and embedded notices remain applicable. `tools/container/prepare_verapdf_materials.py` retains matching source JARs/POMs, exact packaged profile XML and checksums; the [dependency review](DEPENDENCIES.md#distribution-boundaries) documents that boundary. Retained materials do not replace the final candidate's complete corresponding-source/build-resource and recipient-access review. No binary/image distribution is authorized by this notice summary.
