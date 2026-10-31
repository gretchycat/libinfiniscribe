# Source provenance

Original implementations and history: [gretchycat/infinifolder at 9233495](https://github.com/gretchycat/infinifolder/tree/9233495). Existing codecs/renderers are moved without rewriting algorithms; package names and notices are retained for compatibility. New adapters describe Stream inputs/actual outputs. Original-code license selection is pending (LICENSE.md).

| Original path | New path | SHA-256 before move |
| --- | --- | --- |
| `viewer-markdown/src/main/java/com/infinifolder/viewer/MarkdownRenderer.kt` | `libraries/libinfiniscribe/scribe-core/src/main/kotlin/com/infinifolder/viewer/MarkdownRenderer.kt` | `36ff981c2ef5e6ed3f8b95e84dfaaea6fe7af6591148cce1bd6890ebf70838bb` |
| `viewer-markdown/src/test/java/com/infinifolder/viewer/MarkdownRendererTest.kt` | `libraries/libinfiniscribe/scribe-core/src/test/kotlin/com/infinifolder/viewer/MarkdownRendererTest.kt` | `cb1b0c0affdcc3ec7147b58edea27d9c37382889c4670c01493ce858472a61d6` |

## CSV/TSV consumer extraction

At InfiniFolder [7e8a1a2](https://github.com/gretchycat/infinifolder/tree/7e8a1a2), the delimiter detection, parseCsv and formatCsv methods in `viewer-csv/src/main/java/com/infinifolder/viewer/CsvViewerActivity.kt` were relocated into `scribe-core/src/main/kotlin/io/github/gretchycat/infiniscribe/DelimitedText.kt`. The complete pre-move activity SHA-256 is `be469d1ce3b13bced20ec158add39fe81e7f19fe99fff62c3c76c6c091bb17c2`; the companion-method block, dedented four spaces and terminated by LF, hashes to `7ccf1d69bf7509ba19bf2ff3daef87a011e34c81d6165acdfa691abe97542139`. Algorithms are unchanged apart from cooperative cancellation checks. The host retains delegating companion methods for source compatibility.

The four original `viewer-csv/.../CsvParserTest.kt` expectations are also retained as JVM `DelimitedTextTest` coverage; original host tests still exercise the delegates. TableDocuments and its typed metadata/processor adapters are new integration code. Existing trimming/blank-row/heuristic behavior is deliberately preserved, with targeted edge-case, metadata, cancellation and serialization tests.
