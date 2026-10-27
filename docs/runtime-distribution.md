# Runtime distribution checklist

The source container combines NuaForma's native engine, application dependencies, fonts, Java runtimes, independent PDF validators and standard runtime utilities. It contains no LibreOffice or Bubblewrap. This checklist supplements [THIRD_PARTY.md](../THIRD_PARTY.md) and the runtime's [source-access instructions](../deployment/pdf-runtime/SOURCES.md); it does not authorize publication.

From `0.3.0-alpha.2`, apply these obligations through the [single-final-approval release procedure](release-process.md). Existing green CI supplies behavior qualification when runtime inputs are unchanged; release preparation retains actual component/source/identity checks without repeating the full per-container suite.

Before distributing a candidate:

- Record its exact source commit, version, platform and immutable image digest. Inventory the complete final image, including OS packages, both JVMs, fonts, application JARs and validator dependencies.
- Retain original component LICENSE/NOTICE/copyright materials and map them to the exact package/JAR inventories. NuaForma's Apache-2.0 license does not replace third-party terms.
- Provide the matching sources, patches, build materials and source-access instructions required by each included component. Verify anonymous retrieval and checksums against the exact candidate before uploading executable artifacts.
- Review fonts independently. Do not bundle private interoperability fonts. Caller fonts remain request-private resources; technical embedding permissions do not make them distributable runtime assets.
- Verify that notices and source instructions can be extracted from the image without starting the service. Preserve both the server and validator Java source identities and the validator's original profile attribution.
- Complete the native AMD64/ARM64, service, validator isolation, conformance, cleanup, font-mount and shipped-SBOM checks. Reassess security findings and approved dependency/runtime exceptions for this exact candidate.
- Bind reviewed artifacts and source materials to the release approval records. Use the protected [container](container-releases.md) and [Maven](maven-central-releases.md) procedures, followed by the matching website/download update.

Building or signing locally is not authorization to upload, publish, tag or deploy. Keep the exact-candidate qualification and distribution records with the release.
