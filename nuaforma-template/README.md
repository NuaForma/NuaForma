# NuaForma template language

Development specification and Kotlin/Java 17 implementation of a deliberately restricted, format-independent language. `nuaforma-template` is a separately publishable library with only contracts and platform dependencies. No scripting framework, reflection-based object navigation or document library is required.

The current API compiles and renders text and supplies structural sessions. It does not itself read DOCX, modify runs, clone table rows or register a document provider. The [DOCX adapter](../nuaforma-docx/README.md) already uses these sessions to apply the language to native Word structures while preserving formatting. Future format adapters must preserve the same semantics; flattening Word content into this text API and rebuilding the document is not the intended integration.

## Embedded use

```kotlin
val template = Templates.compile("Dear {customer.name}, {#if active}welcome back{#else}welcome{/if}.")
val result = template.render(mapOf("customer" to mapOf("name" to "Ada"), "active" to true))
// Dear Ada, welcome back.
```

```java
CompiledTemplate template = Templates.compile("Reference {reference}");
String result = template.render(Map.of("reference", "NF-2026-001"));
```

Import `dev.nuaforma.template.Templates` and `CompiledTemplate`. For explicit policy, call `render(data, GenerationOptions(...), GenerationContext(...))` using the contracts package. Compilation also accepts a context. Each phase has its own deadline; a reused compiled program does not retain the compilation context, caller data or formatting options. Rendering snapshots and validates the whole data object, creates isolated state and returns a string only on success. The immutable compiled program may be used concurrently.

## Markers and literal text

| Purpose | Syntax |
| --- | --- |
| Insert a scalar | `{customer.name}` |
| Inline or block condition | `{#if active}yes{#else}no{/if}` |
| Repeat a list | `{#each orders as order}{order.reference}{/each}` |
| Missing/null fallback | `{customer.nickname ?? customer.name ?? "Anonymous"}` |
| Number or date formatting | `{amount \| number("#,##0.00")}`, `{date \| date("uuuu-MM-dd")}` |
| Literal braces in the template | `{{` and `}}` produce `{` and `}` |

`#else` is optional. Conditions and repeats can nest and may appear anywhere in text. `#if` and `#each` are followed by a space; marker edges may contain whitespace. There is no `else if` directive: use a nested condition. Block markers do not automatically add, remove or normalize whitespace, newlines or paragraphs. The selected content is concatenated exactly. For example:

```text
You {#if active}have {#if count >= 2}multiple orders{#else}one order{/if}{#else}have no orders{/if}.
```

With `active = true` and `count = 2`, this produces `You have multiple orders.` without introducing a newline. To remove a block's line break, include that line break inside the condition. Unclosed or mismatched markers and unsupported directives fail at compilation, even in a branch that would not be selected. Unescaped unmatched braces are invalid template syntax.

Inserted data is always literal: a value containing `{secret}` or `{#if true}` is never parsed again. It does not need template-brace escaping. String literals inside markers accept straight double/single quotes and typographic forms (`‘ ’ ‚ ‛ “ ” „ ‟ « » ‹ ›`), including mixed opening/closing forms. A quote closes a string when followed by expression punctuation or the end of the expression, ignoring intervening whitespace. Quotes inside words and date-pattern literal text remain intact. When an interior quote would otherwise close the string, escape it explicitly; JSON escapes and `\'` are supported in expression string literals. The parser never normalizes ordinary document text or inserted values. This tolerance is source behavior prepared after `0.3.0-alpha.2`; published alpha.2 requires straight double quotes. Built-in formatters and installed value pipes can form the explicit chains described below. Only the built-in string functions below are callable as expressions; arbitrary application methods and user-defined functions are not supported.

## Paths and scopes

Unqualified paths start in the supplied data object, unless a repetition alias shadows that root name. `data` always means the complete supplied data object. It is not a request object: no authentication metadata, transport settings, resources, options or other generation is reachable through it.

Dot keys use `[A-Za-z_][A-Za-z0-9_]*`. Quoted keys permit any JSON object key, including spaces, punctuation, Unicode and reserved words: `{data["billing-address"]["city name"]}`. Literal nonnegative integer indexes access lists: `{orders[0].reference}`. Index expressions, negative indexes, wildcards, implicit collection projection and bean/property introspection are not supported. All supplied fields remain accessible; a key such as `class` is ordinary map data, not permission to obtain a JVM class.

`{#each orders as order}` evaluates its collection in the enclosing scope. Within its body, `order` is the current element and `loop` contains `index` (zero-based), `number` (one-based), `size`, `first` and `last`. Aliases use the dot-key identifier grammar, except reserved `data`, `loop`, `true`, `false` and `null`. Inner repetitions may shadow an alias and `loop`; exiting them restores the enclosing bindings. Outer aliases with different names remain accessible. Root data remains accessible through `data`, including a root key named `loop`.

```text
{#each orders as order}{loop.number}: {order.reference}{#if !loop.last}, {/if}{/each}
```

For references `A` and `B`, this produces `1: A, 2: B`. An empty list produces nothing. A missing collection fails with `MISSING_VALUE`; null or a non-list collection fails with `INVALID_REQUEST`.

## Expressions and types

Accepted expressions contain paths, JSON strings, finite JSON-style numbers, `true`, `false`, `null`, built-in string function calls, parentheses and the following operators. Function calls bind as values; precedence from highest to lowest is `!`, `< <= > >=`, `== !=`, `&&`, `||`, `??`. Binary comparison/logical operators associate left to right; `??` associates right to left. Parentheses override precedence.

- Conditions and `!`, `&&`, `||` require actual booleans. There is no truthiness for strings, null, numbers or collections.
- `&&` and `||` short-circuit. `??` evaluates its right side only for missing or null values. An unselected branch's data is not accessed, but its syntax is always validated.
- Numeric equality and ordering use decimal values, so `1 == 1.00` is true. Floating-point input is converted from its decimal string representation; calculations and binary-floating-point equality are not language features.
- Equality accepts matching scalar types, with no string/number/boolean coercion. Null equals null; a non-null scalar compared to null yields `false` for `==` and `true` for `!=`. Comparing lists/objects, comparing mismatched non-null types or ordering booleans/null fails.
- String ordering uses case-sensitive JVM lexicographic order, not host-locale collation. Arithmetic, assignment, interpolation within string literals and arbitrary operators are unavailable.

Missing keys, out-of-range indexes and descendants of null yield a missing sentinel, distinct from an explicit null. Using missing data without `??` fails. Traversing an incompatible non-null type (for example, reading `.city` from a string) is a type error and is not hidden by `??`.

Plain scalar insertion prints strings unchanged, booleans as `true`/`false` and numbers as non-scientific decimal text without insignificant trailing zeros. Null produces empty text. Objects/lists are not implicitly converted to strings. Failures use sanitized `GenerationException` diagnostics with a template offset when available, not raw values or nested parser exception messages.

## Null, missing and empty values

No truthiness does **not** mean null or string checks are unavailable. `{#if name}` requires `name` to be a boolean; it does not test whether a string is filled in. `{#if name == null}` works because the comparison produces a boolean. Likewise, `contains(...)` produces a boolean, while `trim(...)` produces a string and cannot be a condition on its own.

| Purpose | Example | Behavior |
| --- | --- | --- |
| Explicit null | `{#if name == null}Explicit null{/if}` | A present null matches; a missing field fails. |
| Missing or null | `{#if (name ?? null) == null}Missing or null{/if}` | The fallback safely handles both cases. |
| Present, non-null string | `{#if (name ?? null) != null}Name: {name}{/if}` | An empty string is still non-null. |
| Empty string | `{#if name == ""}Empty{/if}` | A present empty string matches; null does not. |
| Missing, null, empty or whitespace-only string | `{#if trim(name ?? "") == ""}No name{/if}` | The fallback handles missing/null, then trim removes boundary whitespace. |
| Default text | `{name ?? "Onbekend"}` | Replaces missing/null, but preserves an empty string. |

These examples expect `name` to be a string or null when present. Lists/objects cannot be compared, including against null. A path through an incompatible non-null value is still a type error: `customer.name ?? ""` does not hide a `customer` that is a string instead of an object.

## Built-in string functions

Function names are case-sensitive. Arguments can be value expressions, including paths, `??` fallbacks and nested built-in calls. String arguments must be actual strings: missing values fail with `MISSING_VALUE`; null, numbers, booleans, lists and objects fail with `INVALID_REQUEST`. There is no automatic conversion or implicit null-to-empty behavior.

| Function | Result | Example |
| --- | --- | --- |
| `contains(text, search[, ignoreCase])` | Boolean: literal substring match | `{#if contains(description, "spoed")}Prioriteit{/if}` |
| `startsWith(text, prefix[, ignoreCase])` | Boolean: literal prefix match | `{#if startsWith(reference, "NF-")}NuaForma-referentie{/if}` |
| `endsWith(text, suffix[, ignoreCase])` | Boolean: literal suffix match | `{#if endsWith(filename, ".pdf")}PDF-document{/if}` |
| `trim(text)` | String: remove boundary whitespace | `{trim(customer.name)}` |
| `equalsIgnoreCase(left, right)` | Boolean: case-insensitive equality | `{#if equalsIgnoreCase(status, "approved")}Goedgekeurd{/if}` |

The optional third argument is an actual boolean and defaults to `false`. For example, `contains(description, "spoed", true)` also matches `SPOED`. An empty search/prefix/suffix matches every string, including the empty string. Searches are literal, not regular expressions.

Case-insensitive operations use Java 17's locale-independent simple Unicode code-point mapping (lowercase of uppercase); they do not follow the server's locale or `GenerationOptions.locale`. Accents are not removed, Unicode normalization is not applied, and multi-character case expansions are not performed: `É` matches `é`, but `é` does not match `e`, and `ß` does not match `ss`. Supplementary Unicode characters are processed as whole code points. Simple case matching is not language-specific collation.

`trim` removes leading/trailing characters recognized by Java 17's `Character.isWhitespace` or `Character.isSpaceChar`, including tabs, newlines and non-breaking spaces. Interior whitespace is untouched; a zero-width space is not trimmed. Input data is never modified.

```text
{#if equalsIgnoreCase(trim(status ?? ""), "approved")}Goedgekeurd{/if}
{#if contains(description ?? "", "spoed", true)}Prioriteit{/if}
```

Only these five built-ins are callable. Unknown functions, incorrect argument counts and method syntax such as `name.trim()` fail at compilation, even in an unselected branch. No reflection, callbacks, script execution or regex facility is exposed. Function nesting shares the expression depth limit. String scans, comparisons and trim copies consume the cumulative evaluation-operation budget and check cancellation/deadlines; repeated calls across structural sessions share that budget. Literal substring search is linear in the input/search lengths, including overlapping patterns. Before allocating search buffers, argument UTF-16 lengths must fit the remaining operation budget; this is a conservative capacity check for supplementary characters. Intermediate storage cannot scale beyond this budget. Results containing template markers remain literal.

## Formatting policy

`| number("pattern")` requires a number or null. It uses the explicitly supplied `GenerationOptions.locale` (default `Locale.ROOT`) and half-even rounding. Patterns are bounded to 64 characters and restricted to DecimalFormat's numeric symbols `0 # . , % ; E + -`; invalid patterns fail at compilation. For example, `1.005` with `"0.00"` yields `1.00`. Plain numeric insertion is locale-independent.

`| date("pattern")` requires a string or null. Input is a strict ISO local date (`2026-01-01`) or an ISO timestamp with an explicit offset (`2026-01-01T00:30:00Z`), bounded to 128 characters. A local date remains a date; timestamps are converted to `GenerationOptions.timeZone` (default UTC) before formatting. Patterns use Java 17 DateTimeFormatter syntax, are bounded to 64 characters and use the explicit locale (default `Locale.ROOT`). No implicit local time zone, host locale or guessed input format is used. Invalid dates, offset-free timestamps or fields unavailable on a local date fail. Formatter patterns accept the same mixed outer quotes as other strings. Interior quote shapes are normalized to the date formatter's single-quote syntax, so `date(“dd MMMM yyyy ‘om’ HH:mm')` retains literal `om`. Backslash escapes in formatter patterns remain unsupported.

### Date and time pattern reference

Patterns are case-sensitive. `MM` is a month; `mm` is minutes. Names and abbreviations depend on the explicit locale.

| Pattern | Meaning | Example |
| --- | --- | --- |
| `d` / `dd` | Day of month, without / with padding | `8` / `08` |
| `M` / `MM` | Month number, without / with padding | `3` / `03` |
| `MMM` / `MMMM` | Short / full month name | `Mar` / `March` in English |
| `EEE` / `EEEE` | Short / full weekday name | `Wed` / `Wednesday` in English |
| `yyyy` / `uuuu` | Year of era / continuous year | `2026` / `2026` |
| `yy` | Last two digits of year | `26` |
| `HH` / `hh` / `a` | 24-hour clock / 12-hour clock / AM-PM | `14` / `02` / `PM` |
| `mm` / `ss` / `SSS` | Minutes / seconds / three fractional-second digits | `05` / `09` / `123` |
| `XXX` | Offset, or `Z` for UTC | `+01:00` |
| `VV` / `z` | Zone identifier / short name | `Europe/Amsterdam` / `CET` |
| `D` / `Q` | Day of year / quarter number | `301` / `4` |
| `w` / `YYYY` | Locale-dependent week number / week-based year | Can differ near New Year |

`yyyy` is supported and produces the same result as `uuuu` for modern business dates. `uuuu` also represents year zero and negative years; the input profile remains the ISO forms described above. Prefer it for precise calendar-year semantics, not because `yyyy` is invalid. Avoid uppercase `YYYY` for ordinary calendar dates. See the [complete Java 17 pattern reference](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/format/DateTimeFormatter.html#patterns).

```text
{issuedOn | date("dd-MM-yyyy")}
{issuedOn | date("EEEE d MMMM uuuu")}
{createdAt | date("dd-MM-uuuu 'om' HH:mm")}
{createdAt | date("uuuu-MM-dd'T'HH:mm:ssXXX")}
```

With `nl-NL`, the second pattern renders `2026-10-28` as `woensdag 28 oktober 2026`. Timestamp conversion includes daylight-saving rules and can move the calendar date; date-only input cannot supply clock/zone fields. Two consecutive single quotes inside a date pattern produce a literal apostrophe. Optional pattern sections and other Java patterns are available when their required fields exist on the supplied value.

### Syntax error diagnostics

For complete Maven/Gradle extension builds and packaged-JAR discovery tests, see the [Java 17 pipe starters](../examples/pipe-starters/README.md). They use published `0.3.0-alpha.2` and cover basic prefix, uppercase and object-member selection without requiring the unreleased quote/diagnostic additions.

Source after `0.3.0-alpha.2` adds fixed syntax reasons and bounded expression context. Trusted `LoggingMode.DEVELOPMENT` captures a maximum 256-character excerpt, its starting offset and the error position, including end-of-input. Production does not attach template excerpts. For embedded use inspect `Diagnostic.templateContext`; HTTP and Playground presentation follow the [logging guide](../docs/logging.md). A `template:n` location remains a zero-based UTF-16 offset within the adapter's local text fragment. It is not a Word page or document-wide position. Known secret field expressions are redacted; other business text can be sensitive. Unclosed quotes, missing delimiters, unsupported functions/pipes, argument counts and invalid patterns have distinct fixed reasons.

Null formatting yields empty text; missing formatting fails unless the expression supplies a fallback. The two formatters are separate from the built-in string functions; neither exposes general Java methods or user-installed callbacks.

## Safety and processing budgets

No template can select arbitrary application methods, constructors, reflection, classes, filesystem/network operations or runtime code evaluation. A registered value pipe is an explicitly installed, trusted callback, not sandboxed template code. Data must contain maps with string keys, lists, strings, finite supported numeric values, booleans and null—not beans or other application objects. Mutable input is copied without modification; callers must not mutate it concurrently during the snapshot. Passing an application-supplied map or list implementation is trusted host code, not a sandbox for malicious Java objects.

Existing `GenerationLimits` govern template UTF-8 bytes at compilation, output/expanded UTF-8 bytes, expression/block depth, total repeated elements, cancellation and deadlines. Additional conservative implementation ceilings are 128 levels even if a caller requests more, 4096 characters and 1024 tokens per expression marker, 100,000 template nodes, 1,000,000 evaluation operations, 64 nested data levels and 100,000 data values. String values and object keys cumulatively consume the expanded byte budget. Numbers have at most 1000 digits of precision and a scale between -1000 and 1000; numeric literal text is bounded to 256 characters. Invalid Unicode is rejected.

Nested repeats count every visited element, including repetitions with empty bodies; all output, including literal text, consumes the byte budget. Reuse with a lower depth budget is checked again at rendering. Compilation and rendering regularly check activity. Limits are cooperative and do not forcibly interrupt blocking host callbacks or guarantee a process heap ceiling; process isolation remains a separate hosting responsibility. Failures return no success-shaped partial text.

## Structural template sessions

`TemplateSession.open(data, options, context)` snapshots data once for one generation. `test(expression)` evaluates a strict boolean condition. `branch()` enters its structural body without changing bindings. `repeat(expression, alias)` returns child sessions with local aliases and `loop` metadata; all sessions share cumulative output, operation and repetition budgets. Structural nesting and inline expression/block nesting consume the same depth budget. Sessions are not thread-safe and must not be shared across generations.

`expand(source)` returns immutable, ordered `TemplateFragment` values instead of flattening formatting. UTF-16 `sourceStart`/`sourceEnd` offsets identify the original literal span or complete placeholder. Literal fragments retain their original run formatting; substitutions use the opening marker's formatting. Escaped brace fragments may be shorter than their source span. Repetition may emit the same source range more than once, so consumers must honor fragment order, not sort by offset. Inserted data is literal even when it contains braces.

`validate(source)` and `validateExpression(expression)` check syntax without accessing missing data or consuming output. Format adapters use them to validate unselected structural branches. This facade adds no document-library dependency and leaves the compiled text API unchanged.

<a id="extensible-value-pipes-unreleased-source"></a>

## Extensible value pipes

Value pipes transform JSON-compatible values and preserve intermediate types. A trusted application installs `TemplatePipe` implementations; templates only select registered names. No dependency download, hot reload or scripting engine is involved.

```text
{customer | select("account") | select("total") | number("0.00")}
{missing ?? null | defaultLabel("Not provided")}
{#each orders as order}{order | select("reference") | label("Order: ")}{/each}
```

The grammar is `{expression | name(argumentExpression, ...) | next(...)}` with required parentheses, at most 32 stages and at most 16 positional arguments per custom pipe. Arguments use the existing expression grammar, including aliases, string functions and explicit fallbacks; `||` and `|` inside quoted strings are not separators. Pipe chains are placeholder suffixes, not new functions: pipes are not accepted inside `if`/`each` expressions, parenthesized expression operands or pipe arguments. Missing values/arguments fail before invocation; explicit null is passed through. `OptionDefinition` declarations constrain argument types, defaults and allowed values; required arguments reject null, optional null is passed through. Numeric allowed values compare by decimal value, including integer arguments expressed as `BigDecimal`.

Values, arguments and results may be null, strings, booleans, finite supported numbers, lists and string-key maps containing those values recursively. Custom numeric results are normalized to the existing decimal model. Inputs and results are detached, deeply immutable snapshots; returning the original input is safe. The final result alone is inserted: null is empty, scalars use existing rules, and a map/list must be explicitly converted by another pipe. An arbitrary JVM object is rejected without stringification. Returned template markers remain literal. Existing `number`/`date` pattern grammar, null behavior, locale/time-zone and rounding rules remain unchanged; they can precede/follow value pipes. Formatters produce strings, not implicit numbers/dates for later stages.

### Implementation and registration

Implementations compile against Java-17-compatible `nuaforma-contracts` only and must be thread-safe:

```kotlin
class SelectPipe : TemplatePipe {
    override val definition = TemplatePipeDefinition("select", "Select an object member",
        listOf(OptionDefinition("key", OptionType.STRING, "Member name", required = true)))
    override fun apply(value: Any?, arguments: List<Any?>, context: TemplatePipeContext): Any? {
        context.checkActive()
        if (value == null) return null
        require(value is Map<*, *>)
        return value[arguments.single()]
    }
}
```

Java implements the same `getDefinition()` and `apply(Object, List<?>, TemplatePipeContext)` methods; use `new TemplatePipeDefinition("select", "Select an object member", List.of(new OptionDefinition("key", OptionType.STRING, "Member name", true)))`. Only the evaluated input/arguments, explicit locale/time zone and `checkActive()` are supplied; no implicit root data, request, resources, authentication or arbitrary option object is present.

In the same extension JAR, add `META-INF/services/dev.nuaforma.contracts.TemplatePipe` with one binary implementation class per line, and `META-INF/nuaforma/pipes/example.SelectPipe.properties`:

```properties
pipe.name=select
pipe.class=example.SelectPipe
pipe.version=0.1.0-SNAPSHOT
contract.major=0
contract.minor=2
```

The class must be a public, concrete no-argument ServiceLoader implementation. Before initialization, discovery validates all declarations, UTF-8 service files, exact descriptor keys, class identity/origin and same-major/minimum-minor compatibility. Only local JARs/class directories are supported, not network resources or JPMS module-layer services. Class, descriptor and service entry must originate from the same artifact. Duplicate names/classes, reserved `number`/`date`, unsupported contracts, missing/mismatched metadata and later definition-name mismatches fail with sanitized `INVALID_REQUEST`; no classpath-order fallback exists. Content/value name collisions also fail when combined by a generator, even if never referenced by a template. `image` and `richText` are reserved by the built-in DOCX generator, not by unrelated text engines.

`TemplatePipes.discover(loader)` explicitly selects an application class loader; close a caller-owned URLClassLoader only after its registry/providers are no longer in use. `TemplatePipes.of(listOf(SelectPipe()))` supplies explicitly registered instances without classpath descriptors or any discovery. Both return immutable snapshots with sorted extension `definitions()` reused by source capability integration. No global cache or request-level class loading is introduced.

```kotlin
val pipes = TemplatePipes.of(listOf(SelectPipe()))
val template = Templates.compile("{customer | select(\"name\")}", pipes)
val result = template.render(mapOf("customer" to mapOf("name" to "Ada")))
val session = TemplateSession.open(data, pipes, options, context)
```

```java
TemplatePipes pipes = TemplatePipes.discover(extensionLoader);
CompiledTemplate template = Templates.compile("{customer | select(\"name\")}", pipes);
String result = template.render(Map.of("customer", Map.of("name", "Ada")));
```

The original `Templates.compile(source, context)` and session-opening overloads discover once at compile/open time using the current thread context class loader, falling back to the template library loader. A compiled program retains its registry but not the compilation context, data or loader separately. Rendering never rediscovers implementations. `ContentTemplates.open(data, contentPipes, valuePipes, options, context)` shares an explicit registry with structural scopes and text-only watermark processing. A content pipe must still be the only pipe in its marker: neither preceding nor following value/formatter stages are permitted.

### Limits and trust boundary

A generation and all its structural/watermark scopes share existing operations, depth, repetition, byte and deadline policies, plus at most 4,096 custom value-pipe invocations (also capped by `maxRepetitions`), 100,000 cumulatively copied values and `maxExpandedBytes` cumulatively copied string/key bytes across inputs, arguments/defaults and intermediate results. Copies check cancellation, reject cycles, invalid Unicode and unsupported/non-finite numbers, and have at most 64 levels or the lower configured expression-depth limit. The registry permits at most 128 custom registrations. Callback failures and malformed results are sanitized without input values or original causes; cancellation/resource-boundary codes remain structured. Unknown names and malformed chain syntax fail during validation even in unselected branches; implementation-specific type constraints are enforced on executed values.

Installed extensions have full application-process permissions. They must bound their own allocations/work, avoid retaining request state and cooperate with `checkActive()`. NuaForma checks before/after invocation, but cannot safely force-stop a blocking/allocating arbitrary JVM callback. These limits do not turn extensions or hostile host collection implementations into a sandbox. Templates/REST cannot install code, select a class loader or obtain a callback not in the captured registry.

See the independent [Java-17 Kotlin extension and consumer](../examples/value-pipes/README.md), [DOCX registration](../nuaforma-docx/README.md#value-pipes-unreleased-source) and [operator mount guide](../deployment/pdf-runtime/MOUNTS.md). Source REST capabilities expose these provider-owned catalogs through #60.

<a id="generator-content-pipes-unreleased-source"></a>

## Generator content pipes

Content pipes insert document content; they are separate from chainable JSON value pipes. A selected generator explicitly registers its implementations. The template module supplies no image decoder; the DOCX generator registers the [bounded standard image pipe](../nuaforma-docx/README.md#image-content-pipe) for DOCX and PDF source generation.

The grammar is `{expression | registeredName(argumentExpression, ...)}`. Parentheses are required even with no arguments. Values and arguments use the existing expression grammar, including paths, local aliases, string functions and explicit `??` fallbacks. Arguments are positional, with at most 16; required arguments precede optional arguments. The generator declares types, defaults and allowed values. Omitted optional arguments use their declared default; optional null arguments pass through, while required arguments reject null. Allowed-value constraints apply to non-null arguments using structural JSON equality, with numeric values compared independently of their JVM number type or decimal scale. Numbers from template expressions use the existing `BigDecimal` model. Missing values fail before callback execution; an explicit null input value reaches the implementation, which declares its null behavior. Inserted content remains literal.

```text
{company.name | companyLabel("Legal name: ")}
{company.optionalName ?? null | companyLabel()}
{#each groups as group}{#if group.active}{group.name | companyLabel()}{/if}{/each}
```

There must be exactly one pipe in a content marker. Preceding/following pipes, mixed formatter/content chains and unknown registrations fail during validation, including inside unselected branches. Existing `number("pattern")` and `date("pattern")` formatting is unchanged. Plain `Templates.compile`, `TemplateSession.expand` and requested text watermarks reject content markers rather than stringifying or dropping them.

Format integrations use the additive `ContentTemplates.open(data, pipes, options, context)` facade. Call `validate` on all source locations before document mutation, then use `expandContent` to obtain UTF-16 source spans and structured `ContentResult` values. `branch`/`repeat` retain the same registrations, scoped bindings and cumulative budgets. `validateText` and inherited `expand` explicitly select text-only semantics. Standard results are immutable `ContentResult.Text` and `ContentResult.Empty`; text inherits the opening marker's formatting. The interface is open for trusted generator-owned structured variants: the template transports them without reflection or `toString()`, while the owning generator must explicitly recognize and bound its variants. These are not arbitrary JSON values or host/document-library object wrappers. Built-in DOCX rejects unknown result types before output publication; no implicit fallback exists.

Only the evaluated value, positional arguments and explicit locale/time-zone/cooperative cancellation context reach an implementation. Structured inputs are deeply immutable copies. Registration collisions fail deterministically; no classpath-order selection or template-driven class loading occurs. At most 128 registrations, 16 arguments and 4,096 content executions per session are allowed, additionally limited by the caller's repetition budget. Input copies, expressions, Unicode text and inserted output share the existing operation/output/deadline limits. Extension failures are sanitized, without input values or original causes. These are trusted in-process extensions: a blocking or allocating callback cannot be safely preempted; implementations must check cancellation and bound their own work.

See the [DOCX registration example](../nuaforma-docx/README.md#generator-content-pipes-unreleased-source) and [public contracts](../nuaforma-contracts/README.md#optional-generator-content-pipes-unreleased-source). Source capability endpoint exposure (#60) reuses these optional metadata facets.

## Executable specification

```sh
./gradlew :nuaforma-template:test
./gradlew :nuaforma-server:test --tests 'dev.nuaforma.architecture.*'
./gradlew build generatePomFileForMavenJavaPublication
```

`TemplateLanguageTest` exercises paths, nested mid-sentence conditions, block whitespace, alias restoration, literal insertion, null/missing/type semantics, locale/time-zone formatting, concurrent reuse, cancellation and complexity/expansion limits. Negative cases cover executable constructs and malformed syntax. `JavaTemplateTest` checks Java use on Java 17 and absence of core/docx4j/DOCX classes from this library's runtime. Architecture tests enforce the sixth module's direction and internal boundary with passing and violating fixtures. These checks do not claim Word replacement, layout preservation or PDF support.

`StringFunctionsTest` makes the documented null/string examples executable and covers strict types/arity, empty searches, Unicode, literal matching, overlapping-search reference comparisons, nested/shared budgets, cancellation/deadlines and concurrent compiled-template reuse. DOCX integration additionally checks split-run functions, both text-box forms, structural conditions/table rows and package equivalence to literal-value templates. REST integration checks actual DOCX output and dynamic watermarks against embedded generation. No public API or dependency is added.

For visual regression, the DOCX tests produce `nuaforma-docx/build/string-functions-reference/{generated,expected}.docx` and `nuaforma-docx/build/watermark-reference/{string-functions,dynamic}.docx`. After tests finish, `tools/pdf-evaluation/verify_string_functions.py` uses the already qualified isolated renderer image and compares every rendered page with its literal-equivalent output. Supply `--docker`, `--image`, `--poppler` (the directory containing `pdftoppm.exe`) and a fresh `--output` directory. This optional workstation QA helper reuses the PDF research launcher's prerequisites; it is not an engine API or an image publication step. Visually inspect the generated PNGs as well as the pixel comparison.

`ValuePipesTest` covers typed/nested chains, positional argument constraints, null/missing policy, literal results, immutable copies, invalid results/cycles, shared budgets, cancellation, concurrency and formatter compatibility. `ValuePipeDiscoveryTest` independently compiles Java-17 contracts-only JARs and checks metadata/origin/compatibility before initialization, deterministic collisions and safe failure. `MountedValuePipesTest` exercises the actual server operator snapshot, restart/cleanup, concurrent providers and multipart/embedded watermark parity. `DocxValuePipesTest` compares native package semantics (normalizing only random watermark IDs), split runs, tables, both text boxes and structural scopes, and emits `nuaforma-docx/build/value-pipes-reference/{generated,expected}.docx` for independent raster comparison. The separately packaged Kotlin extension/consumer under `examples/value-pipes` is an additional Java-17 build check, not a test-only compiler dependency in the engine.

The source DOCX generator also supplies [`richText("rtf", trimStart, trimEnd, inline)`](../docs/rich-text.md), with the three booleans defaulting to true. Its definition opts into missing-as-null through contract 0.7. That policy applies only to the evaluated input value of an opted-in content pipe; ordinary placeholders and missing argument expressions remain strict. This source feature is not in alpha.4.
