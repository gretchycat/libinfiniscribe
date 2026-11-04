# Structured text reparsing

These optional modules reconstruct complete, in-memory Unicode documents for display.
They do not open remote resources, save storage, own Android views or apply word wrapping.

| Module | Runtime | Backend |
| --- | --- | --- |
| `scribe-reparse-api` | JVM / Android | Backend interface, selection registry, bounded reader/writer, Stream processor |
| `scribe-reparse-json-android` | Android 26+ | Strict Android JsonReader validation and whitespace-only formatting |
| `scribe-reparse-xml` | JVM / Android | Platform DOM, preserving mixed content, CDATA, namespaces and xml:space |
| `scribe-reparse-yaml` | JVM / Android | SnakeYAML 2.4 node composition/serialization, preserving aliases, tags and comments |
| `scribe-reparse-toml` | JVM / Android | TomlJ 1.1.1 with ANTLR runtime 4.11.1 |

All artifacts use group `io.github.gretchycat.libinfiniscribe`, version `0.1.0`.
Each backend depends on the API; the API depends on the existing Scribe core and
Stream contracts. Backends are not dependencies of the core or one another.
Include only the backends needed by a host. Android SDK discovery controls inclusion
of the JSON Android module, matching the existing optional PDF backend.

## Host integration

```kotlin
val registry = TextReparserRegistry() // discovers linked reparsing handlers
val backend = registry.detect(fileName, mime, original.text)
val display = backend?.let { TextReparsing.reparse(original, it, cancellation) }
```

Declare the required backend modules as dependencies. Each ships its capability
provider; absent backends are not silently assumed. The default registry discovers
the linked `TextReparserHandler` services. An explicit backend list remains a
supported compatibility option. See [runtime capabilities](CAPABILITIES.md).

Run reparsing on a worker before assigning the display text to a wrapping view.
The host retains the original TextDocument and original bytes for editing and
revision-checked publication. Reparsed bytes must not replace that original
merely because a display option was enabled. The host owns caching, fallback
messages, viewport windows and save policy.

`TextReparsing` executes the selected `TextReparseProcessor` through Stream's
registry. Descriptors advertise decoded text-document input and reparsed text
output. Partial documents are rejected. Input remains borrowed and unchanged;
the session owns its output wrapper. Parser errors and cancellation propagate.

Output contains UTF-8 bytes, exact UTF-16 code-unit/encoded-byte/logical-line
counts, backend identity and display purpose. Logical line counts precede word
wrapping. Source charset/checksum provenance remains source information; source
byte length, offsets, seekability and verified-output claims are not carried into
reconstructed output. Current output byte length is an explicit `encodedBytes`
attribute rather than a Unicode-stream length.

The low-level `TextReparser.format` compatibility entry point returns a String
without document metadata. Hosts should use `TextReparsing.reparse` for common
source/output contracts. The registry prioritizes filename extensions, then MIME
hints, then backend-specific content prefixes. A prefix hint does not validate
syntax; the chosen backend must still parse the document.

## Limits

The existing helpers bound output to 16,777,216 UTF-16 code units and check
cancellation while reading/writing. This is a character budget, not a RAM budget.
InfiniFolder additionally limits complete source caching to 4 MiB and keeps long
paragraphs in a bounded display window. These are host policies.

JSON preserves duplicate keys, escaped string contents and number spelling.
XML declines DTDs and external entities, and limits emitted depth to 128.
YAML limits input to 4,194,304 code points, collection aliases to 50 and nesting
to 64. Explicit document markers preserve multiple YAML documents. YAML uses
nodes, not construction of arbitrary application objects. TOML reconstructs
valid typed content but can omit comments and normalize value spelling.
Original source remains available to the host in all cases.

## Verification

Portable checks without Android SDK configuration:

```sh
gradle :scribe-reparse-api:build :scribe-reparse-xml:build :scribe-reparse-yaml:build :scribe-reparse-toml:build
```

With an Android SDK configured, build `:scribe-reparse-json-android:assembleDebug`.
The InfiniFolder workspace runs the Android/Termux harness
`scripts/verify-text-reparser-runtime.py [optional-local-json-file]` against the
actual backend artifacts. It checks tokens, malformed input, cancellation and
round trips without using a font renderer or launching UI.

## Provenance and licenses

The implementations originated in InfiniFolder's uncommitted `text-reparser-*`
modules during the 2026-11-01 text-viewer work. This move preserves their algorithms
and separates the portable modules from the Android JSON backend. Public packages
are now `io.github.gretchycat.infiniscribe.reparse`. No published compatibility
package is removed; the host and its local runtime harness are migrated together.
Stream metadata/processor integration and portable tests are new library adapters.
Original-code licensing remains pending owner selection; see LICENSE.md.

SnakeYAML and TomlJ declare Apache-2.0; ANTLR runtime declares BSD-3-Clause.
Dependency versions are pinned above. License texts are bundled under each
module's `META-INF/libinfiniscribe` resources, with dependency notices. All are
published JVM libraries; no native binary or prebuilt parser source is vendored.
