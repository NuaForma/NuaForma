# NuaForma website

This is a buildless static GitHub Pages website. It never receives templates or generates documents. The complete downloadable requests are submitted to the visitor's own NuaForma service.

Preview from the repository root with `python3 -m http.server 8430 --bind 127.0.0.1 --directory site`. Validate with `python3 tools/site/check_site.py` and `node --check site/app.js`.

Rebuild examples with `python3 tools/site/build_example_kit.py` (python-docx and Pillow). Repackage deployment kits with `python3 tools/site/package_deployment_kits.py`. The builders contain no production dependencies. Only the `site/` folder is published; local QA outputs remain under ignored `build/`.

Verify new document examples with `tools/site/verify_example_kit.py --poppler /path/to/pdftoppm --output build/site-example-check`, using Python with pypdf, Docker and the released alpha image. The output directory must not exist. This checks only the new website samples, not the entire release qualification suite. Review every rendered page before delivery.

The Word example declares explicit Liberation Serif font families, uses opaque JPEG images and omits unused bibliography metadata from the python-docx starter. These choices keep the examples within the alpha's verified PDF profiles; they are not support claims for arbitrary font themes or bibliography parts.

The Website workflow checks pull requests without deploying. After merge, changes to website files publish through the `github-pages` environment. Configure the repository's custom domain in Pages settings; Actions publishing does not require a CNAME source file. Keep HTTPS enforcement enabled once GitHub has issued the certificate.
