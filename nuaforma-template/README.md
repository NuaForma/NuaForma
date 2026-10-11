# NuaForma template language

Development specification and Kotlin/Java 17 implementation of a deliberately restricted, format-independent language. `nuaforma-template` is a separately publishable library with only contracts and platform dependencies. No scripting framework, reflection-based object navigation or document library is required.

The current API compiles and renders text. It does not read DOCX, modify runs, clone table rows or register a document provider. Future format adapters must apply the same semantics to their native structures while preserving layout; flattening Word content into this text API and rebuilding the document is not the intended integration.

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

Inserted data is always literal: a value containing `{secret}` or `{#if true}` is never parsed again. It does not need template-brace escaping. String literals inside markers are double-quoted, with JSON escapes, and may contain braces. One formatter may follow a value expression; arbitrary pipelines or function calls are not supported.

## Paths and scopes

Unqualified paths start in the supplied data object, unless a repetition alias shadows that root name. `data` always means the complete supplied data object. It is not a request object: no authentication metadata, transport settings, resources, options or other generation is reachable through it.

Dot keys use `[A-Za-z_][A-Za-z0-9_]*`. Quoted keys permit any JSON object key, including spaces, punctuation, Unicode and reserved words: `{data["billing-address"]["city name"]}`. Literal nonnegative integer indexes access lists: `{orders[0].reference}`. Index expressions, negative indexes, wildcards, implicit collection projection and bean/property introspection are not supported. All supplied fields remain accessible; a key such as `class` is ordinary map data, not permission to obtain a JVM class.

`{#each orders as order}` evaluates its collection in the enclosing scope. Within its body, `order` is the current element and `loop` contains `index` (zero-based), `number` (one-based), `size`, `first` and `last`. Aliases use the dot-key identifier grammar, except reserved `data`, `loop`, `true`, `false` and `null`. Inner repetitions may shadow an alias and `loop`; exiting them restores the enclosing bindings. Outer aliases with different names remain accessible. Root data remains accessible through `data`, including a root key named `loop`.

```text
{#each orders as order}{loop.number}: {order.reference}{#if !loop.last}, {/if}{/each}
```

For references `A` and `B`, this produces `1: A, 2: B`. An empty list produces nothing. A missing collection fails with `MISSING_VALUE`; null or a non-list collection fails with `INVALID_REQUEST`.

## Expressions and types

Accepted expressions contain paths, JSON strings, finite JSON-style numbers, `true`, `false`, `null`, parentheses and the following operators. Precedence from highest to lowest is `!`, `< <= > >=`, `== !=`, `&&`, `||`, `??`. Binary comparison/logical operators associate left to right; `??` associates right to left. Parentheses override precedence.

- Conditions and `!`, `&&`, `||` require actual booleans. There is no truthiness for strings, null, numbers or collections.
- `&&` and `||` short-circuit. `??` evaluates its right side only for missing or null values. An unselected branch's data is not accessed, but its syntax is always validated.
- Numeric equality and ordering use decimal values, so `1 == 1.00` is true. Floating-point input is converted from its decimal string representation; calculations and binary-floating-point equality are not language features.
- Equality accepts matching scalar types, with no string/number/boolean coercion. Null equals null; comparisons to null return false for non-null scalars. Comparing lists/objects, comparing mismatched non-null types or ordering booleans/null fails.
- String ordering uses case-sensitive JVM lexicographic order, not host-locale collation. Arithmetic, assignment, interpolation within string literals and arbitrary operators are unavailable.

Missing keys, out-of-range indexes and descendants of null yield a missing sentinel, distinct from an explicit null. Using missing data without `??` fails. Traversing an incompatible non-null type (for example, reading `.city` from a string) is a type error and is not hidden by `??`.

Plain scalar insertion prints strings unchanged, booleans as `true`/`false` and numbers as non-scientific decimal text without insignificant trailing zeros. Null produces empty text. Objects/lists are not implicitly converted to strings. Failures use sanitized `GenerationException` diagnostics with a template offset when available, not raw values or nested parser exception messages.

## Formatting policy

`| number("pattern")` requires a number or null. It uses the explicitly supplied `GenerationOptions.locale` (default `Locale.ROOT`) and half-even rounding. Patterns are bounded to 64 characters and restricted to DecimalFormat's numeric symbols `0 # . , % ; E + -`; invalid patterns fail at compilation. For example, `1.005` with `"0.00"` yields `1.00`. Plain numeric insertion is locale-independent.

`| date("pattern")` requires a string or null. Input is a strict ISO local date (`2026-01-01`) or an ISO timestamp with an explicit offset (`2026-01-01T00:30:00Z`), bounded to 128 characters. A local date remains a date; timestamps are converted to `GenerationOptions.timeZone` (default UTC) before formatting. Patterns use Java 17 DateTimeFormatter syntax, are bounded to 64 characters and use the explicit locale. No implicit local time zone, host locale or guessed input format is used. Invalid dates, offset-free timestamps or fields unavailable on a local date fail. Prefer `uuuu` for calendar years; pattern literals may use single quotes inside the double-quoted pattern. Backslash/double-quote escapes in formatter patterns are not part of this initial grammar.

Null formatting yields empty text; missing formatting fails unless the expression supplies a fallback. Formatters are the two specified language operations, not access to general Java methods or user-installed callbacks.

## Safety and processing budgets

No language operation can invoke application methods, constructors, reflection, class loading, arbitrary functions, filesystem/network access or runtime code evaluation. Data must contain maps with string keys, lists, strings, finite supported numeric values, booleans and null—not beans or other application objects. Mutable input is copied without modification; callers must not mutate it concurrently during the snapshot. Passing an application-supplied map or list implementation is trusted host code, not a sandbox for malicious Java objects.

Existing `GenerationLimits` govern template UTF-8 bytes at compilation, output/expanded UTF-8 bytes, expression/block depth, total repeated elements, cancellation and deadlines. Additional conservative implementation ceilings are 128 levels even if a caller requests more, 4096 characters and 1024 tokens per expression marker, 100,000 template nodes, 1,000,000 evaluation operations, 64 nested data levels and 100,000 data values. String values and object keys cumulatively consume the expanded byte budget. Numbers have at most 1000 digits of precision and a scale between -1000 and 1000; numeric literal text is bounded to 256 characters. Invalid Unicode is rejected.

Nested repeats count every visited element, including repetitions with empty bodies; all output, including literal text, consumes the byte budget. Reuse with a lower depth budget is checked again at rendering. Compilation and rendering regularly check activity. Limits are cooperative and do not forcibly interrupt blocking host callbacks or guarantee a process heap ceiling; process isolation remains a separate hosting responsibility. Failures return no success-shaped partial text.

## Executable specification

```sh
./gradlew :nuaforma-template:test
./gradlew :nuaforma-server:test --tests 'dev.nuaforma.architecture.*'
./gradlew build generatePomFileForMavenJavaPublication
```

`TemplateLanguageTest` exercises paths, nested mid-sentence conditions, block whitespace, alias restoration, literal insertion, null/missing/type semantics, locale/time-zone formatting, concurrent reuse, cancellation and complexity/expansion limits. Negative cases cover executable constructs and malformed syntax. `JavaTemplateTest` checks Java use on Java 17 and absence of core/docx4j/DOCX classes from this library's runtime. Architecture tests enforce the sixth module's direction and internal boundary with passing and violating fixtures. These checks do not claim Word replacement, layout preservation or PDF support.
