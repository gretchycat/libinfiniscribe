package io.github.gretchycat.infiniscribe.reparse

import io.github.gretchycat.infinistream.*

class YamlReparserCapabilityProvider : CapabilityProvider {
    override val library = "scribe"
    override fun handlers(): List<CapabilityHandler> = listOf(ReparserCapability(YamlTextReparser))
}
