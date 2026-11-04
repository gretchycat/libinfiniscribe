package io.github.gretchycat.infiniscribe.reparse

import io.github.gretchycat.infinistream.*

class JsonReparserCapabilityProvider : CapabilityProvider {
    override val library = "scribe"
    override fun handlers(): List<CapabilityHandler> = listOf(ReparserCapability(JsonTextReparser) {
        if(android.os.Build.VERSION.SDK_INT>=26)HandlerAvailability() else HandlerAvailability(AvailabilityState.UNAVAILABLE,"Android JsonReader runtime unavailable")
    })
}
