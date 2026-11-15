# Font family and face identity

Selected 0.5.0-alpha.4 qualifies font-family aliases consistently for DOCX and native PDF. A font's extended typographic name can cover designs such as Light, Display or Condensed. These designs retain their precise legacy family names when their legacy and typographic subfamily records identify different groups. Ordinary regular, bold, italic and bold-italic style linking remains supported.

The [OpenType naming specification](https://learn.microsoft.com/en-us/typography/opentype/spec/name) distinguishes the four-style family/subfamily fields from the broader typographic fields. The shared parser pairs subfamily records within the same platform, encoding and language before comparing them. Case and whitespace differences alone do not create a different group. Legacy-only, typographic-only and unpaired records retain their existing behavior.

## Shared helper and source boundaries

`OpenTypeFontNames.families(nameTable: ByteArray): Set<String>` is a Java-callable static helper in the existing `nuaforma-docx` artifact. It reads a raw OpenType `name` table and returns an immutable set. It never changes the input bytes. It does not inspect glyph outlines or embedding rights.

Both metadata readers use this helper: `OpenTypeMetadata` supplies deployment discovery and DOCX embedding; `PdfFontMetadata` validates native PDF declarations. Face flags, SFNT header/table validation and distinct editable/print embedding policies remain in their existing callers. No module, dependency, fallback font or system-font discovery is added.

Font filenames remain arbitrary. Two different files may legitimately share a family while supplying different faces. A named extended variant must not stand in for a base family solely through a broad alias. If the requested family/face is unavailable, conversion retains the explicit font diagnostic.

## Qualification

The normal full build passes 1862 JVM tests with no failures, errors or skips. The normal targeted build passes 47 naming, discovery, declaration and permission tests, including fifteen new regressions. An independent Java 17 consumer verifies the public static helper and immutable result.

Eight real HTTP outputs qualify standard PDF and PDF/A-1b, 2b and 3b through both the server and Playground cleanup/reuse route. All use embedded proper Aptos faces; the sixteen pages remain pixel-identical across routes/profiles at 96 DPI without masks. The cleanup is idempotent and the local source bytes remain unchanged.

The local corpus retains fourteen successful conversions out of 250 unchanged sources. Eleven unaffected PDFs and eighteen pages remain pixel-identical. Three Aptos documents, six pages in total, now use the correct family instead of a Display alias. Independent local Word exports agree on page counts and position-sorted text for all three, and the six page pairs were visually reviewed. Four previously successful pages differ because of this intentional family correction; no existing repository golden is replaced. This does not claim pixel equality with Word.

Maintained regressions cover standard and extended aliases, locale-specific style pairing, metadata boundaries, immutable results, input-byte retention, Java access, deployment conflicts and agreement between deployment and PDF declarations. Existing font permission tests and renderer goldens remain required. Private fonts and corpus files are not repository fixtures.

The Playground uses the same deployment and conversion code without a separate option. The fix is not included in unchanged `0.5.0-alpha.3` artifacts.
