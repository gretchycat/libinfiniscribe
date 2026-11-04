package io.github.gretchycat.infiniscribe.reparse

import io.github.gretchycat.infinistream.*

/** Capabilities come from the parser itself; parser modules register their own provider. */
class ReparserCapability(val parser: TextReparser, private val runtime:()->HandlerAvailability = {HandlerAvailability()}) : CapabilityHandler {
    override val descriptor = HandlerDescriptor("scribe.reparse.${parser.name.lowercase(java.util.Locale.ROOT)}", "scribe", parser.name,
        listOf(FormatSupport(parser.mimeTypes, parser.extensions)), setOf(Operations.REFORMAT),
        metadata = setOf("sourceText", "formattedText"), limitations = listOf("Display-only; source is not rewritten", "16 MiB output character limit"),
        attributes=mapOf("mimeSuffixes" to parser.mimeSuffixes.sorted().joinToString(","),"leadingCharacters" to parser.leadingCharacters.sorted().joinToString("")))
    override fun availability()=runtime()
    override fun <T : Any> service(type: Class<T>): T? = if(type.isInstance(parser)) type.cast(parser) else super.service(type)
}

object ReparserCapabilities {
    val registry get() = RuntimeCapabilities.library("scribe")
    fun parsers(): List<TextReparser> = registry.query(Operations.REFORMAT).mapNotNull { it.handler.service(TextReparser::class.java) }
}
