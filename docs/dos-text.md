# DOS text contracts

`DosTextEncoding.detect` checks a byte sample for Unicode first, then content
clues for CP437/ANSI. Extensions and 8.3 names are secondary hints; they are not
required. Detection reports its charset, DOS-surface eligibility and evidence.
No heuristic can prove the identity of arbitrary legacy single-byte encodings.

`TextEncodingProbe.open` probes at most 16 KiB without moving a positional cursor.
Sequential inputs return a replayable wrapper. The caller owns/closes the returned
source, including when it wraps the original. Failure closes that owned source.

`DosTextDocuments.parse` returns a text grid (80 columns by default), foreground/
background style runs and extracted ANSI Music strings. No bitmap, font, platform
widget, audio device or file write belongs to this parser. CP437 graphics, cursor
moves, VGA/extended colors, erase controls, tabs, CR/LF, DOS EOF and SAUCE trailers
are handled. Unsupported controls are not executed as operating-system commands.
The static document is not a timed BBS animation/terminal session.

Limits: 4 MiB encoded input, 65,536 rows, 262,144 nondefault runs and 4,096 music
strings. Cancellation is checked while parsing and building the result. Limits
fail explicitly instead of publishing truncated success. Hosts retain original
bytes for raw-source viewing/editing, use native selectable text and inject their
own typeface and output backend.

ANSI detection also applies to valid UTF-8, including a UTF-8 BOM. UTF-8 is
decoded before terminal controls are interpreted; CP437 remains the fallback
for legacy art. UTF-8 art with a DOS text filename or SAUCE metadata can use
the same surface without escape codes. The parser preserves Unicode scalar
values, attaches combining marks to the preceding cell, and returns style
offsets in UTF-16 units for Android spans. Printable scalar values occupy one
terminal cell; full Unicode wide-character/emoji terminal layout is not emulated.
