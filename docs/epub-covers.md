# EPUB cover metadata

`EpubCovers.candidates(resources, cancellation, limits)` returns package-relative
`EpubCover(path, mimeType)` candidates without decoding images, extracting a ZIP,
parsing chapters or owning the borrowed resources. Callers use their existing
container and image services to load and render a candidate.

The reader reuses EpubDocuments' container/OPF discovery, XML limits, cancellation,
entity handling and confined DocumentPaths resolver. Candidate priority is EPUB 3
manifest `cover-image`, legacy `meta name="cover"` identifiers, guide cover images,
then images referenced by declared XHTML/SVG cover pages. A manifest item named
`cover` or `cover-image` is also accepted for older generators. It does not select
an arbitrary first illustration as the cover. At most four declared cover pages
and sixteen distinct candidate images are returned; callers may impose smaller
work budgets. Unsupported page CSS/layout is not rendered by this metadata API.

InfiniFolder's icon host supplies bounded Vault-backed sources and decodes the
selected image with Canvas; PDF title-page icons reuse Scribe Android's existing
PdfDocuments renderer. No MOBI/AZW/FB2 reader is introduced. The full EPUB viewer
retains its existing staged-resource rendering path.

The host debug APK builds with this API. No additional tests were added or run;
real-device artwork and provider checks remain pending in the host documentation.
