<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="assets/branding/nuaforma-dark.png">
    <source media="(prefers-color-scheme: light)" srcset="assets/branding/nuaforma-light.png">
    <img src="assets/branding/nuaforma-light.png" alt="NuaForma — rounded teal and navy N logo" width="320">
  </picture>
</p>

# NuaForma

**Turn templates and structured data into documents.**

NuaForma is an open-source document generation project for businesses,
institutions, and developers. Its goal is to combine reusable templates with
structured data to produce documents in multiple formats, through a generic,
self-hostable service.

From a simple letter to an agreement with conditional sections, NuaForma aims
to make document automation accessible without locking users into a proprietary
platform.

> **Project status:** early planning. This repository establishes the project
> vision and license. No working generator, API, or installation package is
> available yet. The capabilities below are planned, not implemented.

## The idea

A generation request combines three things:

- A **template** describing the layout, placeholders, and document logic.
- **Structured data** supplying the values to insert.
- An **output format** specifying the document to produce.

Templates should support more than replacing a name or date. Authors should be
able to include or omit text blocks based on conditions, choose between
alternative sections, and repeat content for lists or tables.

For example, an agreement could include one clause when `x = 1), use another
when `y = 1), and omit a section when neither condition applies. The template
defines those choices; the input data determines the result. The template
syntax and authoring approach have not yet been selected.

## Planned capabilities

- **Placeholders:** insert values from structured input, with formatting for
  dates, numbers, and other common data types.
- **Conditional content:** include, omit, or select blocks using understandable
  template rules.
- **Repeated content:** generate rows or sections from collections.
- **Multiple output formats:** target PDF, Microsoft Word (DOCX), and
  OpenDocument Text (ODT, used by LibreOffice and OpenOffice).
- **PDF profiles:** explore archival output such as PDF/A, with conformance
  validation before claiming support for a specific profile.
- **REST integration:** submit templates and data through an API.
- **Stored templates and data:** generate documents using identifiers instead
  of submitting all inputs with every request.
- **Optional database adapters:** retrieve templates or input data from
  connected systems.
- **Self-hosting and extensibility:** support internal deployments, integrations,
  and custom adaptations.

OpenDocument Spreadsheet (ODS) output is also under consideration. It is a
spreadsheet format, distinct from ODT, and would require its own generation
workflow.

These are design goals, not a commitment that every template can be converted
to every output format with identical layout. Supported template types, output
combinations, and rendering limitations will be documented as they are chosen.

## Who is it for?

NuaForma is intended for teams generating letters, reports, agreements,
statements, and other documents from application data. It should be useful
both as an internal service and as a component integrated into other products.

The project aims to build on suitable open-source tools, with its own code
providing a consistent generation workflow and integration layer. The
implementation language, template engine, and rendering tools are still open
decisions.

## Initial development direction

1. Choose an initial template format and define the structured-data contract.
2. Build a small end-to-end prototype with placeholders, conditions, and repeats.
3. Validate the first output format using representative documents.
4. Expose the workflow through a REST API with clear validation errors.
5. Expand output support, storage adapters, and deployment options.

Security will be part of the design: untrusted templates, rendering processes,
database access, and sensitive document data need explicit boundaries and
resource limits.

## Contributing

The project is at the idea and design stage. Feedback, use cases, example
templates with synthetic data, and technical proposals are welcome.

Do not submit personal data, credentials, or confidential documents. A
contribution guide and development instructions will be added when the initial
implementation is established.

The GitHub organization is [NuaForma](https://github.com/NuaForma).

## License

NuaForma is licensed under the [Apache License 2.0](LICENSE).

The license permits commercial and internal use, modification, and
redistribution, including use in proprietary products, subject to its terms.
It does not require publishing private modifications. Distributed copies must
comply with the license's notice and attribution requirements.

Third-party dependencies retain their own licenses and will be documented
when introduced. The Apache License does not grant trademark rights.

Copyright 2026 NuaForma contributors.

