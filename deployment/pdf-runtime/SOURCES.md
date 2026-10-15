# Corresponding source and third-party materials

This runtime combines separately licensed, unmodified components; it is not wholly Apache-2.0. NuaForma's code and original launcher/build scripts are Apache-2.0. Nothing here restricts recipients' rights under the component licenses. The service requires no activation, licence server or account to obtain the source or run generation.

The default source-build version is `0.1.0-alpha.1`; the image build replaces it with the selected container version. Before binary distribution, the release procedure must make its matching source assets anonymously downloadable at [the versioned release](https://github.com/NuaForma/NuaForma/releases/tag/v0.1.0-alpha.1). A locally built candidate is not evidence those assets have been published. Never distribute it before the source-access gate passes.

The versioned release provides a SHA-256 manifest and these source archives, separately from container binaries:

- `nuaforma-sources-debian-0.1.0-alpha.1.tar`: exact Debian source descriptors, original archives, distribution patches and Debian build rules for all inventoried outer/renderer packages.
- `nuaforma-sources-libreoffice-0.1.0-alpha.1.tar`: matching LibreOffice 26.8.1.1 core, dictionaries, help and translations plus its checksum-pinned external source/build resources. The unused Windows bootstrap binary is not included.
- `nuaforma-sources-java-0.1.0-alpha.1.tar`: matching Temurin/OpenJDK sources and build scripts. The source service target includes validator Java 17.0.20.1+1 and server Java 25.0.4.1+1, so a new release must provide both source trees, their exact `BUILD_SOURCE` scripts and the pinned upstream container recipe; previously published archives remain immutable.
- `nuaforma-sources-jvm-0.1.0-alpha.1.tar`: original application dependency material and veraPDF source JARs, POMs, embedded subcomponent notices and validation profiles. Source JARs are ZIP archives; their source-file headers retain original copyrights and terms.

The signed NuaForma release tag supplies its engine/service source, Dockerfile, fixed launcher scripts and Bubblewrap build instructions. Bubblewrap's exact 0.13.0 source is also retained inside the container at `/usr/share/nuaforma/sources/bubblewrap-0.13.0.tar.xz`. No LibreOffice, Bubblewrap, Java or veraPDF vendor sources are patched by NuaForma; PDF compatibility processing changes only request-private document copies.

Extractable notices and inventories inside the container:

- `/usr/share/doc`, `/usr/share/common-licenses`: outer Debian packages.
- `/opt/nuaforma/renderer/usr/share/doc`, `/opt/nuaforma/renderer/usr/share/common-licenses`: renderer Debian packages; LibreOffice's own `LICENSE` and `NOTICE` are under `/opt/nuaforma/renderer/opt/libreoffice26.8`.
- `/opt/java/openjdk/legal` and `/opt/java/server/legal`: validator Java 17 and server Java 25 module licences, third-party notices and the Classpath/assembly exceptions. Each installation's `release` file identifies its source and build commits. The server installation is present in the service target only; `java-sha256.txt` inventories both complete installations.
- `/opt/nuaforma/application/third-party-materials`: original application dependency notices and source headers, including docx4j subcomponent attribution and ANTLR 2's separate terms.
- `/opt/nuaforma/verapdf-validation/licenses` and `materials`: MPL-2.0 validator material, CC-BY-4.0 profile attribution, matching sources and embedded transitive notices.
- `/usr/share/nuaforma/licenses`: Bubblewrap LGPL texts, Apache-2.0/Moby and other retained runtime notices.
- `/usr/share/nuaforma/*-packages.tsv` and `*-sha256.txt`: exact package versions and runtime JAR checksums.

These files can be copied from a stopped container using `docker create` and `docker cp`; starting the HTTP service is unnecessary. Keep the upstream texts and source-access instructions with redistributed copies. Private operator/request fonts and extensions are not part of the release and are not uploaded with these materials.
