package io.github.gretchycat.infiniscribe.office

import org.apache.poi.openxml4j.util.ZipSecureFile
import java.io.File
import java.io.IOException

internal object OfficeRuntime {
    init {
        val prefix="io.github.gretchycat.infiniscribe.poi.xml.stream."
        System.setProperty(prefix+"XMLInputFactory","com.fasterxml.aalto.stax.InputFactoryImpl")
        System.setProperty(prefix+"XMLOutputFactory","com.fasterxml.aalto.stax.OutputFactoryImpl")
        System.setProperty(prefix+"XMLEventFactory","com.fasterxml.aalto.stax.EventFactoryImpl")
        ZipSecureFile.setMaxEntrySize(32L*1024*1024)
        ZipSecureFile.setMaxFileCount(10000)
    }
    fun check(file:File) {
        if(!file.isFile || file.length()==0L)throw IOException("Document is empty or unavailable")
        if(file.length()>64L*1024*1024)throw IOException("This document exceeds the 64 MiB reader limit")
    }
}
