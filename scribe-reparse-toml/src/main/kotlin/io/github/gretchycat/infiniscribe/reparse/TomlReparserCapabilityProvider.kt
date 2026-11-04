package io.github.gretchycat.infiniscribe.reparse

import io.github.gretchycat.infinistream.*

class TomlReparserCapabilityProvider : CapabilityProvider {
    override val library = "scribe"
    override fun handlers(): List<CapabilityHandler> = listOf(ReparserCapability(TomlTextReparser))
}
