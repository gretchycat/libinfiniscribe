# libinfiniscribe

Structural extraction from gretchycat/infinifolder at 9233495.
Implementation ownership and validation are recorded in SOURCE_PROVENANCE.md.

Build the JVM module using JDK 17+ and Gradle 9.7.1: `gradle :scribe-core:build`. No Android SDK or runtime is required.

Source dependencies: libinfinistream. Clone them beside this repository (or set -PinfiniLibrariesDir). InfiniFolder pins the complete tested set as submodules; standalone builds use these same sources, not unpublished Maven binaries. Android backends remain in the explicit optional scribe-android module, included when an SDK is configured through ANDROID_HOME, ANDROID_SDK_ROOT, android.home or local.properties.

Original-code licensing is pending owner selection; see LICENSE.md. No original implementation is rewritten or relicensed by this move.

## Current boundary

The JVM scribe-core module owns the unchanged MarkdownRenderer (legacy package retained for source compatibility), its eight existing tests, bounded text decoding through TextDocuments, and the existing CSV/TSV delimiter/parser/formatter rules extracted from CsvViewerActivity into DelimitedText. It consumes common byte sources, preserves original bytes for the host's revision/fingerprint checks, exposes actual text/encoding/completeness metadata and never saves an incomplete preview. WebView, CSS presentation surfaces and editor save/recovery UI remain host adapters. Charset selection and HTML output remain unchanged. The renderer and text decoder publish input/output descriptors; generic multi-document processing remains incremental.

TextDocuments.read now negotiates and executes a pull pipeline through Stream's ProcessorRegistry using TextDecodeProcessor. Charset/preview limits configure the processor instance. The target requires actual text-document representation metadata, preventing an encoded text source from being mistaken for an already decoded value. Actual output metadata is validated, errors/cancellation propagate, and input remains borrowed. This remains the complete-document path used by Markdown/HTML and the guarded text/CSV editors. Default text/CSV viewing uses the window readers below.

Decoded TextDocument values describe finite Unicode text, selected sourceCharset, explicit UTF-16 code-unit count and the existing replacement-decoding policy. Their original bytes remain available for revision checks. A verified input checksum is retained as source provenance and is not misreported as verification of transformed output.

## Delimited tables

`TableDocuments.parse(TextDocument, fileName, delimiter?)` executes a registered pull processor and produces a typed `TableDocumentHandle`/`TableDocument`. Actual output metadata describes Unicode cells, delimiter/format, row count, maximum column count, completeness and source provenance/warnings. File-name TSV preference and all existing permissive CSV rules remain: first-line delimiter heuristics (including tie ordering), trimmed fields, skipped empty newline-terminated rows, quoted newlines and permissive unmatched quotes. This extraction does not claim strict RFC CSV normalization. Raw `DelimitedText` helpers remain available for compatibility.

`TableDocuments.format(TableDocument)` executes the reverse structured-to-text processor, preserving the existing quoting, LF line endings and UTF-8 save bytes. The returned TextDocument describes the actual serialized Unicode text and includes its encoded bytes. Edited row/column counts are recomputed; encoded byte length is not misreported as the length of a Unicode value. Truncated-source completeness and warnings propagate; source checksums remain provenance, not output verification. Inputs are borrowed, outputs are in-memory session-owned values, and cancellation is checked during parsing and formatting. Both processors are pull-only and publish their port descriptors.

InfiniFolder's CSV viewer calls these paths for real loads and saves. Android opening, 8 MiB preview policy, original-byte revision checks, edit blocking for partial loads, safe publication/recovery, Save As and UI stay in the host. Table parsing neither reopens sources nor writes storage. The viewer retains metadata without keeping an additional original cell table alive after creating its editable rows.

## Discrete document windows

`TextChunkReader(ByteSource, charset)` and `TableChunkReader(ByteSource, fileName)` consume Stream handles. Their input/output descriptors and actual output metadata distinguish source capabilities from owned Unicode/table values. Real `PositionalReadable.readAt` is preferred; the sequential cursor is never used for a positional source. Eight cached 16 KiB blocks bound retained source data to 128 KiB. Forward-only sources can revisit cached blocks; an evicted range is not advertised as seekable. No reader stages, downloads, scans or decodes the whole source merely to open its first window.

`readPrevious(beforeOffset)` returns the adjacent preceding text window at a known character boundary without scanning the prefix. Text reads default to 16 KiB and 256 newline characters per window. UTF-8/UTF-16 boundaries are retained, and random byte jumps align to complete characters. Offsets are 64-bit, with actual sourceOffset/nextOffset/charset/access/completeness metadata. `findNext` explicitly scans bounded windows on request, including ordinary matches across a window edge. The text window's next-page flag differs from document completeness: reaching EOF from a nonzero offset never makes a partial view safe to overwrite.

CSV/TSV windows contain at most 50 matched records by default, with a 64 KiB scan budget per window. Record offsets come from preceding results, preserving quoted newlines, CRLF and escaped quotes across byte blocks. Delimiter selection and field parsing reuse the existing DelimitedText algorithms. A single large record may use the existing 8 MiB CSV budget. Sparse filters can explicitly scan successive bounded windows through findRows; firstRow retains the header for presentation. Original bytes are returned for revision checks only when the unfiltered window is the entire source.

`readPrevious(beforeOffset, endRowIndex)` scans complete CSV records backward, respecting quoted newlines, escaped quotes, CR/LF/CRLF and logical row numbers. `firstRowIndex` includes skipped records, and `findPreviousRows` searches preceding bounded windows for a sparse filter. The host can discard off-screen windows and recover them later without retaining a global bookmark index. Actual positional capability is still required for evicted source ranges.

`countDataRows()` explicitly scans to EOF using bounded record windows and returns an exact 64-bit data-row total, excluding the first/header row. It reuses the same parser (quoted newlines and skipped blank records included), supports cancellation and does not retain a whole table. Counting requires reading the entire source; a host should use a separate reader on a background worker so the viewport cache/cursor and first display are unaffected. A failed/incomplete count must remain unknown rather than become an estimated total.

Readers own their source by default; ownsSource=false borrows it. Close releases cached bytes and the owned source. Reads are serialized, cancel cooperatively and propagate invalid counts/I/O errors. The Android viewers perform all source work on a cancellable worker and bound the displayed text/rows/columns. Legacy full-document editing is loaded only on explicit edit, under its existing 4 MiB text/8 MiB CSV guard; this checkpoint does not implement paged editing or rewrite publication/recovery.

## Scoped syntax and text styles

`SyntaxHighlighting` supplies bounded lexical profiles and UTF-16 `ScopedTextRange`
values with TextMate scope names and explicit continuation/context/budget state.
`TextStyle`/`TextTheme` describe inheritable colors, emphasis, font family and size.
`TextThemeJson` reads/writes self-contained VS Code tokenColors themes with a
namespaced font extension. The core has no Android types; Gson 2.11.0 is its JSON
dependency (Apache 2.0 notice/license bundled in the core JAR).

See `schema/text-theme.schema.json`, `examples/custom-text-theme.json`, and the host's
`docs/syntax-highlighting.md` for supported selectors, bounds and limitations.
These lexical profiles do not implement the full TextMate grammar interpreter.

## PDF pages (Android backend)

`scribe-android` owns the existing Android `PdfRenderer` integration extracted
from InfiniFolder's PDF viewer. Build with SDK 36:

```sh
gradle -Dandroid.home=/path/to/android-sdk :scribe-core:build :scribe-android:assembleDebug
```

`PdfDocuments.open(File, StreamMetadata)` explicitly requires a local seekable
file. The host stages unsupported sources and retains its cache lease until the
document is closed. The backend performs no hidden downloads or spooling. The
renderer owns its descriptor; failed construction closes that descriptor.
`PdfDocument` is a common `StreamHandle` with actual page-count/backend metadata,
original provenance and warnings. `renderPage(index, viewportWidth, maxDimension)`
returns an owned `PdfPageFrame` with actual raster dimensions, pixel format and
page geometry. Source byte length/verification are not claimed for decoded output.

Page open/render/close and document close are serialized. Every render closes its
page immediately. Cancellation is checked before/after native rendering; native
work itself is not interruptible. Frame close recycles its owned bitmap, while
`takeBitmap()` transfers bitmap lifetime to the presentation surface. Closing a
document does not invalidate already returned frames. The JVM `PageRasterSize`
helper preserves aspect ratio within both raster bounds (default 4096 pixels).
The host retains zoom, navigation, fit modes, cache policy and external-open UI.

The current JVM suite has 99 passing cases, including eight page-size checks
and 25 EPUB/resource/XML cases.
InfiniFolder's `scripts/verify-pdf-runtime.py` checks real Android metadata,
pixels/pages, bounds, frame transfer, failed-request recovery, cancellation,
corrupt-input descriptor cleanup and concurrent rendering/close. Its handset UI,
live remote/archive opening and native backends for other systems need separate
acceptance. Android PDF remains the current backend; PostScript/EPS, LaTeX and
Office rendering are planned, with no new interpreter or Office dependency added.

## EPUB packages and related resources

`DocumentResources` supplies named common `SourceResource` values, with normalized,
case-sensitive paths. `DirectoryDocumentResources` borrows a host-owned directory;
it advertises actual file access, confines canonical paths, skips directory
symlink traversal and preserves original provenance on individual resources.
`DocumentPaths.resolve` resolves package-relative URI references, percent-decodes
once, retains literal plus signs and removes fragments/queries from lookup paths.
These interfaces can be reused for other compound documents; Office parsing and
layout are not implemented by introducing them.

`EpubDocuments.read(resources, fallbackTitle, cancellation, limits)` executes a
registered pull processor and returns an immutable `EpubBook` StreamHandle.
The portable JVM core owns container.xml/OPF/NCX parsing, spine order/non-linear
exclusion, manifest fallback and the existing 2048-character HTML title/heading
fallback. Book output metadata describes actual title, author, chapter count and
package path, retaining source provenance/warnings without encoded length,
positional capabilities or a transformed-output verification claim. Inputs remain
borrowed; every opened XML/title source closes on success, failure or cancellation.

The host/Vault supplies container expansion. Scribe does not open ZIPs, download
books, save storage or own staging/cache leases. It opens metadata and small title
prefixes through common sources; images, CSS and chapter presentation stay separate.
InfiniFolder retains full staged ZIP/resource expansion and its existing cache
capacity policy, WebView rendering, themes, font scaling and navigation. On-demand
archive resource serving and EPUB 3 NAV-label interpretation remain follow-up;
existing EPUB 3 spine/HTML-title fallback is retained.

Standard platform SAX APIs provide XML parsing on JVM and Android, with no new
third-party dependency. External entities/DTDs are never loaded; a streaming byte
guard rejects internal DTD subsets consistently before either platform can expand
them, while allowing NCX external DOCTYPE identifiers, comments and CDATA. This
guard retains encoding detection and uses constant additional memory. Explicit
`EpubReadLimits` can bound XML bytes/events/text/depth and fallback resource search;
defaults retain existing valid-book size behavior. InfiniFolder uses its existing
cache budget for the XML byte limit. Token/thread cancellation is cooperative.

The application's `scripts/verify-epub-runtime.py` exercises actual packaged
Vault/Scribe classes and Android XML behavior, including related resources and
nested ZIP input. WebView layout, linked images/styles, navigation, theme/font
controls and live remote opening require handset acceptance. Synthetic EPUB 2/3,
fallback and invalid books are under the application's document fixtures.
