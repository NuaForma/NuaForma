# Word paragraph-style defaults

Unreleased source work for #288 remains inside the DOCX adapter. No public model, dependency, font substitution or format fallback is added.

## Style selection

An explicit default paragraph style has priority. If there is none, the adapter follows the qualified Microsoft Word behavior: a unique paragraph style with the display name `Normal` is selected independently of its style ID or declaration order. This name comparison is case-insensitive. The independently qualified trimming characters are ordinary spaces, tabs, no-break spaces and ideographic spaces. En spaces and narrow no-break spaces remain part of the name, so they do not activate an implicit Normal style. Other unqualified whitespace padding is rejected. The same qualified name comparison prevents harmless serializer-normalized tabs in `styles.xml` from diverging from the duplicate `stylesWithEffects.xml`; formatting and other names remain strictly compared. An explicit false default flag does not exclude that implicit fallback. If no such style exists, document paragraph/run defaults apply without an arbitrary first-style fallback.

This compatibility behavior is based on genuine exact-source Word exports, rather than a claim that the general OOXML specification mandates every fallback. A style merely having the ID `Normal` does not override a different display name. Explicitly referenced paragraph styles and their inheritance remain authoritative. Existing legacy table paragraph/run hierarchy rules continue to work when the implicit paragraph-style chain is empty.

## Boundaries and source retention

Original default flags are validated before typed binding. Duplicate explicit defaults, ambiguous implicit Normal names, unsupported aliases and malformed flags remain precise errors. Standard on/off spellings are accepted; foreign or unqualified default attributes cannot silently become a different style choice. Existing style-cycle, nesting, font and output-profile restrictions remain in force.

Source definitions are not inserted, reordered or removed. Playground template cleanup retains supported implicit definitions and does not choose a new template style. PDF output uses the same adapter behavior through embedded and REST use.

## Qualification

The combined source candidate passed provider tests, real HTTP and Playground checks and the normal full build (1,759 tests). Fifty-four synthetic exact-source Word pairs cover explicit/default-name precedence, absent Normal styles, reordered definitions, renamed IDs, case/whitespace, disabled default flags and table style inheritance on A4 and Letter. Modern body controls are included; an independently observed modern-table width rounding difference remains outside this change. Fourteen initial native control images were reviewed; no existing golden was replaced. Provider tests retain exact native pixels, independent Word text/marker geometry, paper dimensions, embedded fonts and archival-profile checks.
