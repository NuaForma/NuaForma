# NuaForma website

This is a buildless static GitHub Pages website. It never receives templates or generates documents. The complete downloadable requests are submitted to the visitor's own NuaForma service.

Project documentation starts at [docs/README.md](../docs/README.md); source development and extension instructions have separate guides. Keep the site's documentation links and download READMEs aligned with those entry points. Do not upgrade kit endpoints, image tags or Helm probes to unreleased source behavior: existing alpha.3 kits must remain runnable with their matching immutable image. Repackage changed kit documentation and verify all archive members against their sources.

Preview from the repository root with `python3 -m http.server 8430 --bind 127.0.0.1 --directory site`. Validate with `python3 tools/site/check_site.py` and `node --check site/app.js`.

Run `node --test tools/site/test_tabs.cjs` to verify independent deployment/architecture tab groups and keyboard navigation. AMD64 is the default Compose architecture; choosing ARM64 must not change the selected deployment method.

The HTTP-client tabs default to Bruno and remain independent of deployment selections. Run `python3 tools/site/build_example_kit.py --package-only` to reuse the existing Word/JSON examples and rebuild both collections, their ZIPs, the testset and the complete starter kit. `python3 tools/site/package_complete_kit.py` repackages only the all-in-one download with `Deploy/AMD64`, `Deploy/ARM64`, `Deploy/Helm`, `Collections/Bruno`, `Collections/Postman` and `Testkit`; run it after deployment-only changes. All archive members must match their downloadable sources. No client binaries or runtime dependencies are added to NuaForma.

For real client qualification, install the official Bruno CLI and Newman only into an ignored local tools directory, never the product dependency graph. Run `python3 tools/site/verify_client_collections.py --image ghcr.io/nuaforma/nuaforma:0.1.0-alpha.3 --node /path/to/node --bruno /path/to/@usebruno/cli/bin/bru.js --newman /path/to/newman/bin/newman.js --output build/site-client-check`. This extracts the actual complete kit, starts a fresh loopback-only container with the reviewed isolation settings, requires PDF readiness and runs both clients' 11 requests including all six generation scenarios. It removes only its own labelled test container. The output directory must be fresh; logs/reports remain ignored. This is not a GUI import test or a replacement for visual document review. CLI versions qualified locally: Bruno 4.2.1 and Newman 6.2.3. Neither is bundled for users.

Every release requires a deployed, verified website update, including prereleases and releases that change only libraries or the container. Follow [the website synchronization checklist](../docs/container-releases.md#mandatory-website-synchronization); a dynamic banner update alone is insufficient.

Rebuild examples with `python3 tools/site/build_example_kit.py` (python-docx and Pillow). Repackage deployment kits with `python3 tools/site/package_deployment_kits.py`. The builders contain no production dependencies. Only the `site/` folder is published; local QA outputs remain under ignored `build/`.

Verify new document examples with `tools/site/verify_example_kit.py --poppler /path/to/pdftoppm --output build/site-example-check`, using Python with pypdf, Docker and the released alpha image. The output directory must not exist. This checks only the new website samples, not the entire release qualification suite. Review every rendered page before delivery.

The Word example declares explicit Liberation Serif font families, uses opaque JPEG images and omits unused bibliography metadata from the python-docx starter. These choices keep the examples within the alpha's verified PDF profiles; they are not support claims for arbitrary font themes or bibliography parts.

The Website workflow checks pull requests without deploying. After merge, changes to website files publish through the `github-pages` environment. Configure the repository's custom domain in Pages settings; Actions publishing does not require a CNAME source file. Keep HTTPS enforcement enabled once GitHub has issued the certificate.
