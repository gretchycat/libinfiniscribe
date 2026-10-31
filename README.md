# libinfiniscribe

Structural extraction from gretchycat/infinifolder at 9233495.
Implementation ownership and validation are recorded in SOURCE_PROVENANCE.md.

Build using JDK 17+ and Gradle 9.7.1. Android adapters need SDK 36/min API 26.
Run `gradle :scribe-core:test` and the relevant assemble task.

Source dependencies: libinfinistream. Clone them beside this repository (or set -PinfiniLibrariesDir). InfiniFolder pins the complete tested set as submodules; standalone builds use these same sources, not unpublished Maven binaries. Android codec/protocol backends remain in explicit Android modules.

Original-code licensing is pending owner selection; see LICENSE.md. No original implementation is rewritten or relicensed by this move.

## Current boundary

The JVM scribe-core module owns the unchanged MarkdownRenderer (legacy package retained for source compatibility), its eight existing tests, and bounded text decoding through TextDocuments. It consumes common byte sources, preserves original bytes for the host's revision/fingerprint checks, exposes actual text/encoding/completeness metadata and never saves an incomplete preview. WebView, CSS presentation surfaces and editor save/recovery UI remain host adapters. Charset selection and HTML output remain unchanged. The renderer and text decoder publish input/output descriptors; generic multi-document processing remains incremental.

TextDocuments.read now negotiates and executes a pull pipeline through Stream's ProcessorRegistry using TextDecodeProcessor. Charset/preview limits configure the processor instance. The target requires actual text-document representation metadata, preventing an encoded text source from being mistaken for an already decoded value. Actual output metadata is validated, errors/cancellation propagate, and input remains borrowed. This is the text/Markdown/HTML/CSV load path used by the app, not an unused parallel API.

Decoded TextDocument values describe finite Unicode text, selected sourceCharset, explicit UTF-16 code-unit count and the existing replacement-decoding policy. Their original bytes remain available for revision checks. A verified input checksum is retained as source provenance and is not misreported as verification of transformed output.
