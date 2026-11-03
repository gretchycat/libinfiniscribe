package io.github.gretchycat.infiniscribe.mobi

import io.github.gretchycat.infiniscribe.*
import io.github.gretchycat.infinistream.*
import java.io.File
import java.io.IOException

internal interface MobiOutput { fun writePart(path:String,data:ByteArray) }
internal object MobiNative {
    init {System.loadLibrary("infinimobi")}
    external fun extract(path:ByteArray,output:MobiOutput):Array<ByteArray>
    external fun cover(path:ByteArray):ByteArray?
}

object MobiDocuments {
    private fun check(file:File) {if(!file.isFile || file.length() !in 1..64L*1024*1024)throw IOException("MOBI source is unavailable or exceeds 64 MiB")}
    fun read(file:File,name:String,output:DocumentOutput,cancellation:CancellationToken=CancellationToken.NONE,
        input:StreamMetadata=FileSourceResource(file).metadata):ReadingDocument {
        check(file);cancellation.check();var bytes=0L
        val sink=object:MobiOutput {
            override fun writePart(path:String,data:ByteArray) {
                cancellation.check()
                require(path.matches(Regex("[a-zA-Z0-9._-]+"))){"Unsafe book resource path"}
                bytes+=data.size
                if(bytes>64L*1024*1024)throw IOException("Book resources exceed reader limit")
                output.open(path).use {target ->
                    var offset=0
                    while(offset<data.size){cancellation.check();val count=minOf(65536,data.size-offset);target.write(data,offset,count);offset+=count}
                }
            }
        }
        val result=MobiNative.extract(file.absolutePath.toByteArray(Charsets.UTF_8),sink)
        cancellation.check()
        val strings=result.map {it.toString(Charsets.UTF_8)}
        return ReadingDocument(strings[0].ifBlank {name},strings[1],strings.drop(3).mapIndexed {i,path->ReadingChapter(path,"Chapter ${i+1}")},
            StreamMetadata(name=name,content=ContentType(DataKind.STRUCTURED,format="reflow-document"),
                provenance=input.provenance.copy(transformations=input.provenance.transformations+"scribe.libmobi.read")),strings[2].ifBlank {null})
    }
    fun cover(file:File,cancellation:CancellationToken=CancellationToken.NONE):ByteArray? {
        check(file);cancellation.check()
        return MobiNative.cover(file.absolutePath.toByteArray(Charsets.UTF_8)).also {cancellation.check()}
    }
}
