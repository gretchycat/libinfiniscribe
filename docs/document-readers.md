# Reflow document and ebook readers

These optional backends were added for InfiniFolder's first content-reading pass. They do not promise faithful desktop pagination or editing.

## Contracts

`ReadingDocument` contains title, author, ordered `ReadingChapter` resource paths, `StreamMetadata` and an optional cover path. `DocumentOutput.open(normalizedRelativePath)` returns a host-owned `ByteSink`. A parser closes sinks it opens; it does not own the output directory, cache policy or source lease. The host must confine paths and account capacity, and release partially written output on failure.

`DocumentFormats` describes reader routes, not authoritative storage MIME identity. Explicit text/media/PDF/EPUB identities retain their readers. `DocumentXml` reuses the existing bounded, external-entity-disabled EPUB XML parser. `FictionBooks` takes a common `SourceResource`; `OpenDocuments` borrows `DocumentResources` and never closes them itself.

## Modules and use

- `scribe-core`: common models, HTML escaping, XML adapter, FB2, format routes.
- `scribe-office`: `OfficeDocuments.read(file, name, output, token, extensionHint, inputMetadata)` for Word/PowerPoint; `SpreadsheetDocument.open(file, token)` for Excel; `OpenDocuments.read(resources, ...)` and `.workbook(resources, token)` for ODF. The caller closes `SpreadsheetBook`; borrowed ODF resources still belong to the caller.
- `scribe-poi`: shaded POI runtime, consumed using its explicit `androidJar` configuration. StAX, Commons and logging namespaces are private. A real sRGB color value adapter supports reading color values, not AWT drawing.
- `scribe-mobi-android`: `MobiDocuments.read(file, name, output, token, inputMetadata)` and `.cover(file, token)`. Reconstructs supported unencrypted MOBI/KF8 HTML/resources through libmobi.
- `scribe-rtf`: `RtfDocuments.read(source, name, output, token)` produces escaped Unicode text HTML via RTF Parser Kit.

Worksheet reads return `SpreadsheetSheet` with actual one-based row numbers, sparse cell positions, column count and a `limited` indicator. Limits are explicit in `SpreadsheetLimits`; formulas use cached results, without calculation. All new Office routes are read-only. Unknown image codecs and unsupported object/layout features remain absent or visibly labeled rather than being simulated.

## Native source and licenses

`scribe-mobi-android/src/main/cpp/third_party/libmobi/UPSTREAM.md` pins the vendored source. The source is unmodified libmobi 0.12 under LGPL-3.0-or-later; the JNI adapter/build configuration is separate. License copies survive Android packaging under `META-INF/libinfiniscribe-mobi`. To rebuild the shared library on Termux, use:

```sh
sh scribe-mobi-android/src/main/cpp/build-mobi.sh /path/to/output "$JAVA_HOME"
```

A normal Android SDK/NDK build uses `src/main/cpp/CMakeLists.txt`. Both build routes link Android zlib and produce `libinfinimobi.so`, with 16 KiB page alignment. To use a modified backend, rebuild this shared library from the included source and rebuild the application against the resulting Scribe module. The backend remains a dynamically linked shared library. Termux currently emits ARM64; other ABIs/NDK are not validated by this implementation build.

POI 5.5.1 and RTF Parser Kit 1.16.0 use Apache-2.0. Transitive POI licenses/notices are collected from resolved JARs under `META-INF/libinfiniscribe-office`; RTF attribution is under `META-INF/libinfiniscribe-rtf`. Source references: [POI](https://poi.apache.org/components/index.html), [poi-on-android](https://github.com/centic9/poi-on-android), [libmobi](https://github.com/bfabiszewski/libmobi), [RTF Parser Kit](https://github.com/joniles/rtfparserkit).

## Limits and acceptance

Input files accepted by the file-based readers are limited to 64 MiB; XML/resource and workbook-preview bounds are implemented separately. The host is responsible for staging limits before this file check. Parsing is synchronous; call it on a cancellable worker. Cancellation is cooperative and cannot interrupt every internal native/POI parse step.

The owner broadly confirmed the document-reading pass in the host on 2026-11-03.
That report does not establish every MOBI variant, layout, worksheet/formula case
or remote/provider path. The 2026-11-04 runtime-discovery gate builds the app and
standalone host and exercises registration/selection; Office/MOBI/RTF modules have
no independent executable JVM document suites in that gate. Real backend/Android
and format-variant checks remain necessary. Errors are surfaced, never successful
empty documents. Discover linked readers through the typed services documented in
[capabilities](../CAPABILITIES.md); no InfiniFolder initialization is required.
