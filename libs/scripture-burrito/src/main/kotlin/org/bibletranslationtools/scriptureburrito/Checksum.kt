package org.bibletranslationtools.scriptureburrito

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName


@Serializable
class Checksum {

    @SerialName("md5")
    var md5: String? = null

    @SerialName("sha3-256")
    var sha3256: String? = null

    @SerialName("sha3-512")
    var sha3512: String? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Checksum) return false

        if (md5 != other.md5) return false
        if (sha3256 != other.sha3256) return false
        if (sha3512 != other.sha3512) return false

        return true
    }

    override fun hashCode(): Int {
        var result = md5?.hashCode() ?: 0
        result = 31 * result + (sha3256?.hashCode() ?: 0)
        result = 31 * result + (sha3512?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "Checksum(md5=$md5, sha3256=$sha3256, sha3512=$sha3512)"
    }
}
