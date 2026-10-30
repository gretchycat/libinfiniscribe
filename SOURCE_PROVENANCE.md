# Source provenance

Original implementations and history: [gretchycat/infinifolder at 9233495](https://github.com/gretchycat/infinifolder/tree/9233495). Existing codecs/renderers are moved without rewriting algorithms; package names and notices are retained for compatibility. VideoView input binding and time-formatting were extracted from VideoPlayerActivity; platform surface, controls, listeners and leases remain host-owned. New adapters describe Stream inputs/actual outputs. Original-code license selection is pending (LICENSE.md).

| Original path | New path | SHA-256 before move |
| --- | --- | --- |
| `viewer-markdown/src/main/java/com/infinifolder/viewer/MarkdownRenderer.kt` | `libraries/libinfiniscribe/scribe-core/src/main/kotlin/com/infinifolder/viewer/MarkdownRenderer.kt` | `36ff981c2ef5e6ed3f8b95e84dfaaea6fe7af6591148cce1bd6890ebf70838bb` |
| `viewer-markdown/src/test/java/com/infinifolder/viewer/MarkdownRendererTest.kt` | `libraries/libinfiniscribe/scribe-core/src/test/kotlin/com/infinifolder/viewer/MarkdownRendererTest.kt` | `cb1b0c0affdcc3ec7147b58edea27d9c37382889c4670c01493ce858472a61d6` |
