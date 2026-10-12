# Third-party dependency notices

NuaForma is Apache-2.0 licensed. Dependencies keep their own licenses; NuaForma does not relicense them. Standard library JARs refer to dependencies through Maven metadata and do not shade, copy or modify their classes. Consumers distributing a combined application must retain the licenses, copyright notices and applicable NOTICE files of the actual dependency artifacts, including their bundled subcomponents.

## DOCX integration

The `nuaforma-docx` runtime uses docx4j 17.1.0, with its reference JAXB implementation. docx4j is maintained by Plutext Pty Ltd. Its [license](https://github.com/plutext/docx4j/blob/VERSION_17_1_0/legals/LICENSE) is Apache-2.0; its [NOTICE](https://github.com/plutext/docx4j/blob/VERSION_17_1_0/legals/NOTICE) lists bundled sources with additional terms, including BSD, MIT, Microsoft Public License and W3C-related material. Preserve that upstream NOTICE and those licenses when redistributing the unmodified docx4j artifacts. Do not describe the complete dependency stack as exclusively Apache-2.0.

Runtime coordinates and exact resolved versions are recorded in the module lockfiles. The reviewed licensing groups are:

| Runtime components | License family |
| --- | --- |
| docx4j core, generated objects, copy, JAXB selector; JAXB SVG bindings; relocated Xalan; Apache Commons, FontBox/PDFBox IO, XML Graphics; QDox; SparseBitSet; Error Prone annotations; Kotlin and JetBrains annotations | Apache-2.0, with upstream subcomponent notices where applicable |
| JAXB API, JAXB runtime/core/txw2, Jakarta Activation, Angus Activation and istack runtime | Eclipse Distribution License 1.0 / BSD-style terms |
| ANTLR 2/3, StringTemplate and JAXB plugins runtime | BSD-style terms |
| MBassador, Checker Framework qualifiers and SLF4J API | MIT |
| SLF4J Commons Logging bridge | Apache-2.0 |

Licenses were checked against the pinned Maven POMs (including parent inheritance) and upstream artifact license material. For JAXB, see the [reference implementation license](https://github.com/eclipse-ee4j/jaxb-ri/blob/master/LICENSE.md) and [API license](https://github.com/jakartaee/jaxb-api/blob/main/LICENSE.md). EDL is a permissive distribution license, distinct from EPL. Preserve the actual artifact notices rather than using this summary as replacement license text.

DOCX-only consumers acquire no PDF conversion backend, Office automation dependency, server framework, log implementation or paid docx4j component. Local Word rendering of synthetic fixtures is an optional fidelity check only and does not form part of NuaForma's runtime or distributable dependencies.

## REST integration

The server uses [Ktor 3.6.0](https://github.com/ktorio/ktor/blob/3.6.0/LICENSE), Kotlinx Coroutines 1.11.0, Kotlinx Serialization 1.11.0 and Kotlinx IO 0.9.1 under Apache-2.0, plus Apache-2.0 Typesafe Config 1.4.9 and MIT SLF4J API 2.0.19. They remain separate artifacts, not shaded classes or dependencies of the embedded engine libraries. Preserve their original license/copyright and any applicable notice material in a redistributed service. Ktor's test host and its additional clients, test/debug and logging artifacts are test-only and not part of the production service distribution. A locally built application archive is not a publicly audited or published combined runtime.

## Barcode verification assets and tools

DOCX tests include the unmodified Libre Barcode 39 font under SIL Open Font License 1.1, with its upstream copyright and license in `nuaforma-docx/src/test/resources/fonts/OFL.txt`. The adjacent README records its pinned source and checksum. It is not included in production JARs or installed by NuaForma. Font licenses remain independent of the engine license; callers are responsible for their font usage rights. The engine follows technical OpenType embedding permissions without requiring license evidence or an additional consent declaration.

Optional local scan verification uses ZXing-C++ 3.1.1, Apache-2.0 licensed, through its Python wheel with no additional runtime requirements declared. This tool is not a Gradle, CI or production dependency. The reviewed [upstream license](https://github.com/zxing-cpp/zxing-cpp/blob/v3.1.1/LICENSE) and [release](https://github.com/zxing-cpp/zxing-cpp/releases/tag/v3.1.1) belong to that tool, not to the font. The independently published wheel is checksum-verified before local installation into ignored QA storage.

## Approved PDF renderer direction

LibreOffice is bundled only in the locally built [runtime qualification image](deployment/pdf-runtime/README.md), not in library JARs or a published image. Its [upstream licensing](https://www.libreoffice.org/licenses/) includes MPL-2.0 and version-specific third-party terms. This guide does not replace the actual installation's LICENSE, NOTICE, copyright material or source distribution. Follow the [distribution checklist](docs/libreoffice-licensing.md) before redistributing a renderer/runtime, and include an exact-package notice inventory and matching source provision with that distribution. A NuaForma Apache-2.0 LICENSE alone is not sufficient for a combined runtime. Locally available proprietary fonts used for private interoperability checks must not be copied into a redistributable image.

Bubblewrap 0.13.0 is an unmodified separately executed LGPL-2.1-or-later helper. The qualification image retains its COPYING/COPYING.LIB and the exact source archive with build instructions in Dockerfile. The adapted Moby seccomp profile is Apache-2.0; its upstream attribution, commit, original checksum and changes are recorded in `deployment/pdf-runtime/NOTICE`, with Apache-2.0 text included in the image. Debian/Temurin materials and bundled font notices remain independent requirements; no combined image is publicly distributed before its release-specific audit.

The optional PDF module adds Apache PDFBox 3.0.8 for structural output validation in an isolated child JVM. Preserve the original [LICENSE](https://github.com/apache/pdfbox/blob/3.0.8/LICENSE.txt) and [NOTICE](https://github.com/apache/pdfbox/blob/3.0.8/NOTICE.txt), including bundled additional terms. FontBox/IO were already locked through docx4j; Commons Logging 1.4.0 is an additional Apache-2.0 transitive dependency whose artifact notices must also be retained. The separate validator classpath is staged without DOCX, core, server or test libraries. No PDFBox classes are shaded into NuaForma library artifacts.
