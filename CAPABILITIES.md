# Runtime capabilities — scribe

Updated 2026-11-07.

## Linked dependencies

Stream via `scribe-core`; optional modules declare POI, RTFParserKit, SnakeYAML, TOMLJ or Android/native libmobi requirements. No dependency on the browser application.

Dependencies must be packaged/linked, not assumed to be present. Standalone source
builds resolve sibling repositories through includeBuild (or
`-PinfiniLibrariesDir=/path/to/libraries`). Android modules need SDK 36, min API 26.
A library dependency is allowed; an InfiniFolder application/UI dependency is not.

## Public discovery and execution

`ScribeCapabilities.registry`, `ReadingCapabilityHandler`, `SpreadsheetCapabilityHandler`, `ReparserCapabilities`; typed services include text/table processors, PDF, font inspection/rendering and hex document creation.

Use `RuntimeCapabilities.library("scribe")` for the common query, probe, callback and
revision API. The schema and registration contract are documented in the linked
libinfinistream repository's `CAPABILITIES.md`. Providers ship a standard
`META-INF/services/io.github.gretchycat.infinistream.CapabilityProvider` resource.
Constructing a provider must not connect, open input or initialize the application.

Register a reading/spreadsheet/reparser handler in its own module provider. Reader viewers discover it and honor its input/staging plan. Reparser selection derives its MIME/suffix/leading-character hints from the parser. New presentations supply a ViewerComponent independently.

Queries retain overlapping handlers. Availability is separate from metadata-only
identity. Resource probes are bounded/cancellable and can narrow operations; real
API execution still validates input. The plain-text handler also recognizes
unassociated files from a bounded printable-content prefix. The file selector
includes opaque MIME candidates so text files with uncommon suffixes can be chosen;
InfiniScribe probes the referenced bytes before selecting a viewer, and declines
binary-looking data. MIME and extension hints alone do not make an unknown file text.
Use existing Stream sources, positional I/O, owned outputs and typed processors;
do not duplicate decoders in consumers.

## Limits

Office/ODF/MOBI/RTF/FB2 are bounded read-only reading views; stock CSV editing remains only for delimited tables. PDF requires Android and a local seekable file. Font rendering requires Android, separate from portable table inspection. DRM remains unsupported. Stream pipeline registrations currently include text decoding and table parse/format, reusing the facade negotiators.

Discovery is for installed classpath components, not a downloaded code loader.
Android/native display and live remote behavior require device checks in addition
to JVM tests. Descriptive catalog exports do not prove runtime support.

## Standalone build and tests

```sh
gradle :scribe-core:test :scribe-reparse-api:test :scribe-reparse-xml:test :scribe-reparse-yaml:test :scribe-reparse-toml:test :scribe-office:test :scribe-rtf:test --offline
```

No browser process is required. Keep existing format regression suites; capability
tests invoke actual services/parsers rather than scripted success responses.

## New format modules

New document, text, spreadsheet, font, and structured-text handlers must validate content with their parser or encoding checks, then expose source-derived metadata through `MetadataCapabilityHandler`. For text, encoding and decoded length are useful facts; for documents, report only parsed structure and fields. Follow the shared [Stream conformance rule](../libinfinistream/CAPABILITIES.md#format-module-validation-and-metadata-requirements).

## Host audit record

InfiniFolder's 2026-11-04 runtime-discovery audit exercises this repository through
its standalone Gradle test task, and also builds the main and combined standalone
viewer APKs. The host `docs/runtime-capabilities.md` records exact commands, fresh
counts, failures repaired during the gate and unverified device paths. This is
separate from the historical extraction evidence in SOURCE_PROVENANCE.md.
