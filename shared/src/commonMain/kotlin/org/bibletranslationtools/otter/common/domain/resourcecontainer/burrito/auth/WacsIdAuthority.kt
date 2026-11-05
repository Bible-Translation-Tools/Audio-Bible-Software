package org.bibletranslationtools.otter.common.domain.resourcecontainer.burrito.auth

import kotlinx.serialization.json.JsonObject
import org.bibletranslationtools.scriptureburrito.IdAuthoritiesSchema
import org.bibletranslationtools.scriptureburrito.IdAuthority
import org.bibletranslationtools.scriptureburrito.IdentificationSchema
import org.bibletranslationtools.scriptureburrito.PrimaryIdentification

/** Names WACS as the id authority in exported Scripture Burrito metadata. */
class WacsIdAuthority : AuthProvider {
    override fun createIdAuthority(): IdAuthoritiesSchema {
        val authority = IdAuthoritiesSchema()
        val wacs = IdAuthority()
        wacs.name = hashMapOf("en" to "Wycliffe Associates Content Service")
        wacs.id = "https://content.bibletranslationtools.org/"
        authority["wacs"] = wacs
        return authority
    }

    override fun createIdentification(): IdentificationSchema {
        return IdentificationSchema().apply {
            primary = PrimaryIdentification().apply {
                this["id"] = JsonObject(emptyMap())
            }
            name = hashMapOf("en" to "Wycliffe Associates Content Service")
        }
    }
}
