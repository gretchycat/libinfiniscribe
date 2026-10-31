# Source provenance

Original implementations and history: [gretchycat/infinifolder at 9233495](https://github.com/gretchycat/infinifolder/tree/9233495). Existing codecs/renderers are moved without rewriting algorithms; package names and notices are retained for compatibility. New adapters describe Stream inputs/actual outputs. Original-code license selection is pending (LICENSE.md).

| Original path | New path | SHA-256 before move |
| --- | --- | --- |
| `viewer-markdown/src/main/java/com/infinifolder/viewer/MarkdownRenderer.kt` | `libraries/libinfiniscribe/scribe-core/src/main/kotlin/com/infinifolder/viewer/MarkdownRenderer.kt` | `36ff981c2ef5e6ed3f8b95e84dfaaea6fe7af6591148cce1bd6890ebf70838bb` |
| `viewer-markdown/src/test/java/com/infinifolder/viewer/MarkdownRendererTest.kt` | `libraries/libinfiniscribe/scribe-core/src/test/kotlin/com/infinifolder/viewer/MarkdownRendererTest.kt` | `cb1b0c0affdcc3ec7147b58edea27d9c37382889c4670c01493ce858472a61d6` |

## CSV/TSV consumer extraction

At InfiniFolder [7e8a1a2](https://github.com/gretchycat/infinifolder/tree/7e8a1a2), the delimiter detection, parseCsv and formatCsv methods in `viewer-csv/src/main/java/com/infinifolder/viewer/CsvViewerActivity.kt` were relocated into `scribe-core/src/main/kotlin/io/github/gretchycat/infiniscribe/DelimitedText.kt`. The complete pre-move activity SHA-256 is `be469d1ce3b13bced20ec158add39fe81e7f19fe99fff62c3c76c6c091bb17c2`; the companion-method block, dedented four spaces and terminated by LF, hashes to `7ccf1d69bf7509ba19bf2ff3daef87a011e34c81d6165acdfa691abe97542139`. Algorithms are unchanged apart from cooperative cancellation checks. The host retains delegating companion methods for source compatibility.

The four original `viewer-csv/.../CsvParserTest.kt` expectations are also retained as JVM `DelimitedTextTest` coverage; original host tests still exercise the delegates. TableDocuments and its typed metadata/processor adapters are new integration code. Existing trimming/blank-row/heuristic behavior is deliberately preserved, with targeted edge-case, metadata, cancellation and serialization tests.

## Random-access document windows

DocumentChunks.kt and its tests are new integration code over libinfinistream, added after handset reports of slow/crashing multi-megabyte previews. They retain DelimitedText parsing rules, add bounded byte caches/character and record boundaries, and expose actual window metadata. No Android API or third-party parser was introduced into scribe-core.

## PDF backend extraction

At InfiniFolder `7eed041`, `viewer-pdf/src/main/java/com/infinifolder/viewer/PdfViewerActivity.kt`
(SHA-256 `a82c82b2fb836022615a8207888ed51aa123866de38268a00c2edb9432420869`)
directly opened Android PdfRenderer descriptors and rendered ARGB_8888 pages.
`scribe-android/.../PdfDocuments.kt` extracts that existing platform backend into
the library and adds explicit document/frame ownership and common metadata.
Page operations are serialized and pages close after rendering. The pure JVM
PageRasterSize helper applies one scale to both bounded axes; ordinary page
resolution and the existing white background/display render mode are retained.
Host navigation, zoom, staging leases and external-open UI remain in InfiniFolder.
This is integration/lifecycle code, not a replacement PDF parser or a new dependency.

## EPUB package extraction

At InfiniFolder `28fcc48`, `viewer-epub/src/main/java/com/infinifolder/viewer/EpubViewerActivity.kt`
(SHA-256 `34e4e751dfdcf2d701683ced5c0d87a5a71156721707077f9a43814be955ed1d`)
owned ZIP extraction, container.xml/OPF/NCX parsing and HTML-title fallback.
`EpubDocuments.kt` moves the structural rules into the portable core over common
related resources. Platform-neutral SAX events replace Android XmlPullParser
events; first metadata values, spine/non-linear rules, manifest order/numbering,
NCX title priority and the bounded HTML regex fallback are retained. URI resolution
now preserves literal plus signs, handles fragment-bearing hrefs and resolves NCX
references relative to the NCX file. Internal DTD subsets are explicitly unsupported
and external DTD identifiers do not trigger resource/network access.

`DocumentResources.kt`, `EpubXmlInput.kt`, typed output/pull integration and tests
are new adapter code. ZIP expansion moves separately to libinfinivault; Scribe
neither depends on Vault/Store nor writes storage. No additional dependency or
license change was introduced. WebView, cache leases, themes and controls remain
in InfiniFolder.
