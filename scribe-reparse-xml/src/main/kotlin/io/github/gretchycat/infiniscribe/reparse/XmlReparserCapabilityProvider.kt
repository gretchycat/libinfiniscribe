package io.github.gretchycat.infiniscribe.reparse

import io.github.gretchycat.infinistream.*

class XmlReparserCapabilityProvider : CapabilityProvider {
    override val library = "scribe"
    override fun handlers(): List<CapabilityHandler> = listOf(ReparserCapability(XmlTextReparser))
}
