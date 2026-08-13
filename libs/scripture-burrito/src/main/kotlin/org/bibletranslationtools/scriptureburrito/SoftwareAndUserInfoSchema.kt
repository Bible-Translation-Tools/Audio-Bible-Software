package org.bibletranslationtools.scriptureburrito

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName



@Serializable
class SoftwareAndUserInfoSchema {

    @SerialName("softwareName")
    var softwareName: String? = null

    @SerialName("softwareVersion")
    var softwareVersion: String? = null

    @SerialName("userId")
    var userId: String? = null
    
    @SerialName("userName")
    var userName: String? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SoftwareAndUserInfoSchema) return false

        if (softwareName != other.softwareName) return false
        if (softwareVersion != other.softwareVersion) return false
        if (userId != other.userId) return false
        if (userName != other.userName) return false

        return true
    }

    override fun hashCode(): Int {
        var result = softwareName?.hashCode() ?: 0
        result = 31 * result + (softwareVersion?.hashCode() ?: 0)
        result = 31 * result + (userId?.hashCode() ?: 0)
        result = 31 * result + (userName?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "SoftwareAndUserInfoSchema(softwareName=$softwareName, softwareVersion=$softwareVersion, userId=$userId, userName=$userName)"
    }
}
