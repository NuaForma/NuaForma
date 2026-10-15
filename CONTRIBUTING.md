# Contributing to NuaForma

Bug reports, synthetic examples, documentation improvements and code contributions are welcome. To use NuaForma, start with the [README](README.md); to create a private extension, use the [extension guide](docs/extensions.md). Neither requires contributing upstream.

## Before you start

Check the [existing issues](https://github.com/NuaForma/NuaForma/issues) before reporting a problem or proposing a feature. Discuss architectural changes, new dependencies, public contracts and significant output-behavior changes before implementation. Use an issue-linked branch for agreed work and keep the PR focused.

Bug reports should include the exact NuaForma version, runtime/platform and a minimal synthetic template/request, expected versus actual behavior, and relevant font/renderer/profile details. Remove customer data, credentials and private fonts. Do not post secrets or confidential documents in public issues or diagnostic logs. Development mode may log document values; read the [logging/privacy guide](docs/logging.md) before sharing output.

## Set up and verify

Follow the [development guide](docs/development.md) for Java 17/25 toolchains, building, tests, local packaging and independent consumers. Use the checked-in Gradle Wrapper and retain its Unix executable bit. Normal development needs no maintainer signing secrets or registry credentials.

For implementation changes, run affected tests during development and the full build before declaring completion. Add regression cases for normal, boundary and invalid input. Test real document processing rather than mocking the behavior being added. Rendering/font/conversion changes require relevant integration checks, rendered-page inspection and independent barcode/PDF-profile validation; text/XML assertions alone do not prove layout. For prose-only changes, review the diff and validate links/commands; run applicable generation/example checks if those are affected.

## Design and dependencies

- Follow [ARCHITECTURE.md](ARCHITECTURE.md): public contracts across modules and NuaForma-owned facades with component-specific `adapters` for external integrations.
- Preserve Java 17 library compatibility; server/Playground use Java 25. Keep test/build dependencies out of production and GUI assets out of production server/images.
- Keep templates restricted, data request-private, operations bounded and API errors sanitized. Installed extensions are trusted code; do not weaken renderer/validator isolation to make tests pass.
- Review dependencies for maintenance, licenses, security and transitive/JVM compatibility. Update [dependency reviews](DEPENDENCIES.md), notices and deliberate locks when applicable; introducing a dependency needs prior agreement.
- Preserve supported document formatting. Explain intentional behavior changes and compatibility impact; never hide regressions by silently regenerating expected output.

Use English for repository documentation, public API documentation, identifiers and commit messages. Keep prose soft-wrapped and avoid duplicating rules across guides. Agent-specific instructions live in [AGENTS.md](AGENTS.md); workstation-specific agreements are not distributed contributor requirements.

## Commits and pull requests

Cryptographically sign commits and use Conventional Commits: `feat(scope): add ...`, `fix(scope): preserve ...`, `docs: clarify ...`. Use an English imperative description and `Refs #<issue>` where relevant. Mark breaking changes explicitly with `!` and a `BREAKING CHANGE:` footer describing the impact. Never commit secrets, private signing keys, generated previews or unrelated local files.

Open a PR against `main` using the repository template. Explain behavior changes, compatibility and meaningful trade-offs; use `Closes #<issue>` only when acceptance criteria are met. Changes to `main` require a PR and required checks, including `Build (Java 17)`; do not bypass protections. CODEOWNERS assigns maintainers. A contributor need not publish an artifact or deploy the website to propose a change.

**Every PR must assess documentation impact before submission.** Update relevant guides, examples, README/index, architecture decisions, API documentation and deployment instructions when affected. Record why no documentation change is needed when that is the case. Also assess Playground impact: controls, capability/schema interpretation, exports, warnings and tests must stay aligned, or have an explicit tracked dependency without premature support claims.

Keep implemented-but-unreleased capabilities distinct from published artifacts. Release preparation, image publication and website deployment require their own authorization; updating docs does not grant it. Follow the [container](docs/container-releases.md) and [Maven](docs/maven-central-releases.md) guides when explicitly preparing a release.
