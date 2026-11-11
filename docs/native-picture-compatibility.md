# Native picture and label metadata compatibility (source #226)

This source change is not part of published `0.4.0-alpha.7`. The shared PDF model, provider contracts, output profiles and dependencies remain unchanged. The maintainer approved the additive image-normalization facade described below.

## Embedded JPEG pictures

The DOCX adapter accepts embedded `.jpg` and `.jpeg` pictures through the existing qualified DrawingML image layouts. Standalone 8-bit grayscale/RGB baseline and progressive JPEGs are decoded with the Java SE codec into opaque RGB PNG resources for the neutral PDF model. Physical dimensions, crop rectangles, anchoring and supported page-band placement still come from DrawingML. This does not enable mixed text/image paragraphs, additional drawing effects or externally linked pictures.

The approved additive `DocxImages.normalize(bytes, context)` facade in `nuaforma-docx` reuses its existing `adapters.imageio` decoder. It returns fresh PNG bytes, exposes no codec types and keeps both Java overloads usable on Java 17. The native adapter retains JPEG-specific validation and cumulative request budgets; ImageIO ownership and architecture rules remain unchanged.

The decoder uses memory streams and platform codecs, without changing global ImageIO settings or consulting paths/URLs. It rejects malformed markers, truncated/trailing streams, codec warnings and unsupported frame/component formats. CMYK, higher bit depths and unsupported JPEG processes remain explicit failures. Metadata is not interpreted as template content; EXIF orientation does not override the declared DrawingML geometry. No image metadata is copied into the generated PNG. DOCX output retains its original embedded JPEG; normalization belongs only to the PDF print model.

Limits remain bounded: 4 MiB encoded input and normalized output per picture, at most 8,192 pixels per dimension, 16 megapixels cumulatively per reader, and 16 MiB cumulative normalized bytes. The hosting `maxExpandedBytes` can tighten both budgets; decoding reserves twelve bytes per pixel before allocation for the decoded and normalized rasters. A request-local cache avoids decoding the same media part for every repeated page band. Every source reference still counts toward the retained-byte budget before the neutral model copies the image; cache hits cannot bypass this limit. Cancellation/deadline checks surround decoding, input/output operations and pixel-row normalization. Existing archive, shared renderer and PDF output budgets still apply.

## Sensitivity-label metadata

Microsoft defines the [Sensitivity Label Information Part](https://learn.microsoft.com/en-us/openspecs/office_standards/ms-oi29500/c0599e21-b77f-475e-99e0-bd647f60bcbb) with the `http://schemas.microsoft.com/office/2020/mipLabelMetadata` namespace and a package-level classification-label relationship. The qualified part is `docMetadata/LabelInfo.xml`, with `labelList` containing bounded `label` elements. The [classification-label definition](https://learn.microsoft.com/en-us/openspecs/office_file_formats/ms-offcrypto/80e2b2e1-bd48-415d-a3b9-aa61a108f651) describes the label identifiers, state, assignment method and content-marking flags.

The reader validates the exact internal relationship, singular content-type declaration, label namespace/attributes, GUIDs, boolean values and unique tenant identifiers before excluding this metadata from its in-memory print model. Known `Standard`/`Privileged` assignments, disabled/removed labels and marking flags 0–7 are qualified. Encryption flags, unknown policy actions, extensions, external targets, malformed metadata and duplicate labels remain unsupported. The input package is not rewritten or stripped by this print-model normalization.

Existing visible header/footer/watermark objects remain normal document content and must independently satisfy the source profile. The converter neither fetches a tenant policy nor synthesizes missing label markings. PDF conversion does not implement Microsoft Purview policy enforcement, label propagation or encryption. Handling these package identifiers must not be advertised as protected-output support.

## Regression coverage and Playground

Synthetic regression cases cover valid/invalid metadata and relationships, JPEG stream formats and limits, cancellation, independent concurrent requests, and two-page A4/Letter documents with body cropping and different header/footer image sizes. The integration test compares every rendered page with the equivalent existing PNG route, checks retained marking text and DOCX bytes, and requests independent validation for standard PDF and PDF/A-1b/2b/3b. These comparisons establish image-path compatibility; they are not a general Word fidelity claim.

The same native reader serves PDF generation and Playground cleanup, so supported JPEG pictures and validated labels no longer need removal solely because of their package type. Unsupported constructs still fail or are reported by cleanup according to its existing policy. There is no new UI option or permissive conversion mode. Existing golden files remain unchanged.

The local third-party corpus and its outputs stay outside the product repository. Repeated corpus runs report first-failure changes; a successful conversion alone does not establish complete visual agreement with Word.
