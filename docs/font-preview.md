# Font inspection and Android preview

`scribe-core` supplies `FontFormats` and `FontDocuments.inspect(File)` for bounded
TTF/OTF/TTC/OTC directory/name/variation metadata. Its immutable result contains
all collection faces; the file remains caller-owned. Limits: 64 MiB, 256 faces,
4,096 tables/name records per face, 8 KiB per name and 64 variation axes. Invalid
offsets, table lengths and collection structures raise an IOException. Worker
interruption cancels inspection.

`scribe-android` supplies `FontTypefaces.open(file, document, faceIndex)`. It calls
Android `Typeface.Builder.setTtcIndex` and returns the actual loaded Typeface, or
throws if the platform cannot load the face. No fallback family is configured.
The caller owns any local-file staging lease for the preview's lifetime. Native
widgets perform shaping/rasterization; glyph decoders are not reimplemented.

WOFF/WOFF2, Type 1 and legacy bitmap containers are unsupported by this API.
Variable fonts use their default instance. Color/variable glyph support depends
on the platform. Android may use fallback glyphs within an otherwise successfully
loaded face, so specimen UIs should explain that distinction. Font inspection does
not install fonts or change system text preferences.

The Android app's `viewer-font` module provides sample text, sizes, light/dark
specimens, face selection and metadata presentation. Canvas continues to own its
graphics/ANSI font resources.
