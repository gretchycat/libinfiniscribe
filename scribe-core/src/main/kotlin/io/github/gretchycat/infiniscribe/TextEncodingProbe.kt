package io.github.gretchycat.infiniscribe

import io.github.gretchycat.infinistream.*

/** Prefix inspection preserves positional capability, or replays exactly the bytes read from a pipe. */
data class DetectedTextSource(val source: ByteSource, val detection: DosTextEncoding.Detection)
object TextEncodingProbe {
    fun open(source: ByteSource, name: String = "", cancellation: CancellationToken = CancellationToken.NONE): DetectedTextSource {
        val count=minOf(source.metadata.length ?: 16384L,16384L).toInt()
        var owner=source
        try {
            cancellation.check()
            val sample=if(source is PositionalReadable)
                ProbeReader(source,source.metadata,ProbeBudget(maxBytes=16384,maxNanos=5_000_000_000),cancellation=cancellation).bytes(0,count)
            else ReplayableSource(source,16384).also { owner=it }.peek(count)
            cancellation.check()
            return DetectedTextSource(owner,DosTextEncoding.detect(sample,name,source.metadata.length?.let {it<=sample.size} ?: (sample.size<count)))
        } catch(error:Throwable) {
            try {owner.close()} catch(close:Throwable){error.addSuppressed(close)}
            throw error
        }
    }
}
