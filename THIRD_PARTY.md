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

No PDF converter, Office automation dependency, server framework, log implementation or paid docx4j component is added. Local Word rendering of synthetic fixtures is an optional fidelity check only and does not form part of NuaForma's runtime or distributable dependencies.

## Barcode verification assets and tools

DOCX tests include the unmodified Libre Barcode 39 font under SIL Open Font License 1.1, with its upstream copyright and license in `nuaforma-docx/src/test/resources/fonts/OFL.txt`. The adjacent README records its pinned source and checksum. It is not included in production JARs or installed by NuaForma. Font licenses remain independent of the engine license; callers are responsible for their font usage rights. The engine follows technical OpenType embedding permissions without requiring license evidence or an additional consent declaration.

Optional local scan verification uses ZXing-C++ 3.1.1, Apache-2.0 licensed, through its Python wheel with no additional runtime requirements declared. This tool is not a Gradle, CI or production dependency. The reviewed [upstream license](https://github.com/zxing-cpp/zxing-cpp/blob/v3.1.1/LICENSE) and [release](https://github.com/zxing-cpp/zxing-cpp/releases/tag/v3.1.1) belong to that tool, not to the font. The independently published wheel is checksum-verified before local installation into ignored QA storage.
