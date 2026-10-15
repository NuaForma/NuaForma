# Synthetic image fixtures

`image-jpeg.base64` is an original 96×48-pixel teal rectangle with a navy center, authored by NuaForma under the project Apache-2.0 license. It contains no external photograph, user data or third-party asset. Native service checks read the fixed Base64 text, so they need no image-authoring dependency. PNG/alpha-PNG fixtures are built reproducibly by the standard-library-only `image_fixture` function in `qualify_service.py`.

The JPEG was generated with Java SE 17 ImageIO. To deliberately regenerate it from the repository root, run `java -Djava.awt.headless=true tools/container/fixtures/BuildImageFixture.java tools/container/fixtures/image-jpeg.base64` using JDK 17, review the resulting image/diff and rerun native checks. Normal builds never rewrite this fixture.
