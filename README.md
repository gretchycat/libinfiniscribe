# libinfiniscribe

Structural extraction from gretchycat/infinifolder at 9233495.
Implementation ownership and validation are recorded in SOURCE_PROVENANCE.md.

Build the JVM module using JDK 17+ and Gradle 9.7.1: `gradle :scribe-core:build`. No Android SDK or runtime is required.

Source dependencies: libinfinistream. Clone them beside this repository (or set -PinfiniLibrariesDir). InfiniFolder pins the complete tested set as submodules; standalone builds use these same sources, not unpublished Maven binaries. Android codec/protocol backends remain in explicit Android modules.

Original-code licensing is pending owner selection; see LICENSE.md. No original implementation is rewritten or relicensed by this move.

## Current boundary

The JVM scribe-core module owns the unchanged MarkdownRenderer (legacy package retained for source compatibility), its eight existing tests, bounded text decoding through TextDocuments, and the existing CSV/TSV delimiter/parser/formatter rules extracted from CsvViewerActivity into DelimitedText. It consumes common byte sources, preserves original bytes for the host's revision/fingerprint checks, exposes actual text/encoding/completeness metadata and never saves an incomplete preview. WebView, CSS presentation surfaces and editor save/recovery UI remain host adapters. Charset selection and HTML output remain unchanged. The renderer and text decoder publish input/output descriptors; generic multi-document processing remains incremental.

TextDocuments.read now negotiates and executes a pull pipeline through Stream's ProcessorRegistry using TextDecodeProcessor. Charset/preview limits configure the processor instance. The target requires actual text-document representation metadata, preventing an encoded text source from being mistaken for an already decoded value. Actual output metadata is validated, errors/cancellation propagate, and input remains borrowed. This is the text/Markdown/HTML/CSV load path used by the app, not an unused parallel API.

Decoded TextDocument values describe finite Unicode text, selected sourceCharset, explicit UTF-16 code-unit count and the existing replacement-decoding policy. Their original bytes remain available for revision checks. A verified input checksum is retained as source provenance and is not misreported as verification of transformed output.

## Delimited tables

`TableDocuments.parse(TextDocument, fileName, delimiter?)` executes a registered pull processor and produces a typed `TableDocumentHandle`/`TableDocument`. Actual output metadata describes Unicode cells, delimiter/format, row count, maximum column count, completeness and source provenance/warnings. File-name TSV preference and all existing permissive CSV rules remain: first-line delimiter heuristics (including tie ordering), trimmed fields, skipped empty newline-terminated rows, quoted newlines and permissive unmatched quotes. This extraction does not claim strict RFC CSV normalization. Raw `DelimitedText` helpers remain available for compatibility.

`TableDocuments.format(TableDocument)` executes the reverse structured-to-text processor, preserving the existing quoting, LF line endings and UTF-8 save bytes. The returned TextDocument describes the actual serialized Unicode text and includes its encoded bytes. Edited row/column counts are recomputed; encoded byte length is not misreported as the length of a Unicode value. Truncated-source completeness and warnings propagate; source checksums remain provenance, not output verification. Inputs are borrowed, outputs are in-memory session-owned values, and cancellation is checked during parsing and formatting. Both processors are pull-only and publish their port descriptors.

InfiniFolder's CSV viewer calls these paths for real loads and saves. Android opening, 8 MiB preview policy, original-byte revision checks, edit blocking for partial loads, safe publication/recovery, Save As and UI stay in the host. Table parsing neither reopens sources nor writes storage. The viewer retains metadata without keeping an additional original cell table alive after creating its editable rows.
