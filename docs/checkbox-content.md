# Static checkbox content (source work for #149)

This source implementation is qualified for the bounded profile below. It is selected for 0.5.0-alpha.1; source qualification does not itself publish artifacts.

The generator evaluates a normal expression before inserting a static inline drawing:

```text
{accepted | checkbox()}
{(gender == "Vrouw") | checkbox()}
{equalsIgnoreCase(gender, "vrouw") | checkbox(false)}
{accepted ?? false | checkbox(true, "check", "cross")}
```

The three positional arguments are `withBox`, `trueMark` and `falseMark`.
Their defaults are `true`, `"check"` and `"empty"`. Mark values are exactly
`"check"`, `"cross"` and `"empty"`. For example, `checkbox(false, "check", "cross")`
inserts a bare check for true and a bare cross for false. An unboxed empty mark
inserts nothing, including no reserved space.

Input must be an actual boolean. Strings, numbers and null are errors; a missing
path retains the normal missing-value error. Use `?? false` when absence should
mean false. Parentheses, comparisons, existing expression functions and normal
quotation normalization use the existing evaluator. The content pipe is terminal:
it cannot be followed by another pipe or chained after a value pipe.

Ordinary paragraph and hyperlink text remain the insertion boundary. Existing
restrictions for field instructions/results, content controls, notes and comments
remain in force. Literal argument types, enums and arity are checked even in an
unselected branch; data-dependent arguments are evaluated only in selected branches.
Explicit null does not override a declared non-null argument default.

DOCX output can retain a containing hyperlink. Native PDF rejects navigation,
underline/strikeout/superscript on inline vectors and bidi paragraphs containing non-text objects;
those combinations are outside the currently qualified source profile.

## Geometry and formatting

The square is 0.8 times the effective surrounding text size, including inherited
paragraph and character styles. Its bottom edge sits on the text baseline. It
inherits explicit RGB text color and bold weight; automatic color is black. Unsupported theme
color constructs remain outside the native PDF source profile.

Coordinates use a 1000-unit view box, with a 70-unit normal or 100-unit bold opaque
stroke and round caps and joins. The outline visits (50,50), (950,50), (950,950),
(50,950) and closes. A boxed check visits (220,520), (420,740), (800,260). A boxed
cross has independent diagonals between (250,250)/(750,750) and (250,750)/(750,250).
Without a box, the check uses (50,500), (360,872), (950,128), and the cross uses
(50,50)/(950,950) and (50,950)/(950,50). Bare marks therefore fill the available
space without changing the reserved inline dimensions or surrounding text positions.

The drawing is one atomic inline object for wrapping and alignment. DOCX contains
an inline DrawingML shape; PDF contains stroked paths. There is no symbol glyph,
image resource, font lookup or interactive checkbox control for the mark. Ordinary
surrounding text still needs its normal fonts and embedding permissions. Existing
watermark restrictions, including refusal for PDF/A-1b, are unchanged.

## Additive contracts

Approved contract 0.10 adds `ContentResult.Vector`, `VectorPath` and `VectorPoint`
without changing existing constructors. A vector has 1–8 paths, at most 64 points,
and a stroke width of 10–100 units. Each path has 2–16 points (at least 3 when
closed). Coordinates and the entire stroke fit the view box. Collections are
defensive immutable copies. Shapes contain only opaque strokes, without fills,
curves, resource references or executable data.

`ContentResult.Vector.strokeWidth` is the normal-text base weight. The DOCX adapter
increases it by 10/7 for effective bold text, capped at 100 and at the paths' available
stroke margin. Custom vector content therefore keeps its declared view-box bounds.
The neutral PDF model receives the actual final width from the qualified drawing;
it does not apply that text-weight adjustment again.

The independent PDF model provides `PdfVectorRun`, `PdfVector`, `PdfVectorPath`
and `PdfVectorPoint`. The adapter maps the geometry; the PDF module gains no
dependency on template/provider contracts. Earlier constructors retain their JVM
descriptors. Kotlin consumers handling every `PdfInline` subtype exhaustively
must handle the additive vector subtype when adopting this version. Both source
providers declare minimum contract 0.10; older provider minima remain compatible
with a 0.10 host. No extra artifact or dependency is introduced.

`checkbox` is a reserved built-in content-pipe name, alongside `image` and
`richText`. The generator retains the 128-registration budget: at most 125 custom
content pipes can accompany these three built-ins.

The native reader validates the complete supported DrawingML structure, dimensions,
coordinates and stroke properties. A shape's name is never an input trust signal.
Per-document geometry budgets and cancellation apply before output publication.

## REST and Playground

Embedded calls, REST document generation and the Playground use the same provider
registration and insertion path. No checkbox-specific HTTP option or Playground
renderer is introduced. Capability discovery exposes the pipe's arguments, defaults,
non-chainable status and actual available output profiles. The Playground's existing
content-pipe catalog displays that metadata automatically. Upload a DOCX containing
the examples above and provide normal boolean/string JSON data.

## Qualification

Qualification includes Java 17
consumers, DOCX generation, standard PDF and independently validated PDF/A-1b/2b/3b,
Word-export comparison, reviewed new visual goldens, transport parity and the full
build. Existing golden resources must remain unchanged.

The [qualification report](../tools/pdf-evaluation/own-engine/CHECKBOXES.md) records
the independent Word exports, measured differences and reviewed new golden files.
