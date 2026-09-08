package com.babycam.overlay

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID

/** One saved camera: a name, its RTSP URL, optional credentials, and whether it's currently in use. */
data class CameraProfile(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var url: String,
    var username: String = "",
    var password: String = "",
    var enabled: Boolean = true
) {
    /** RTSP URL with username/password injected as userinfo, unless already embedded or no username set. */
    fun authenticatedUrl(): String {
        if (username.isBlank() || url.isBlank()) return url
        return runCatching {
            val schemeIdx = url.indexOf("://")
            if (schemeIdx == -1) return url
            val scheme = url.substring(0, schemeIdx + 3)
            val rest = url.substring(schemeIdx + 3)
            if (rest.contains("@")) return url
            val encodedUser = URLEncoder.encode(username, "UTF-8")
            val encodedPass = URLEncoder.encode(password, "UTF-8")
            "$scheme$encodedUser:$encodedPass@$rest"
        }.getOrDefault(url)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("url", url)
        put("username", username)
        put("password", password)
        put("enabled", enabled)
    }

    companion object {
        fun fromJson(json: JSONObject): CameraProfile = CameraProfile(
            id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = json.optString("name", ""),
            url = json.optString("url", ""),
            username = json.optString("username", ""),
            password = json.optString("password", ""),
            enabled = json.optBoolean("enabled", true)
        )
    }
}

fun List<CameraProfile>.toJsonString(): String {
    val array = JSONArray()
    forEach { array.put(it.toJson()) }
    return array.toString()
}

fun parseCameraProfiles(json: String): List<CameraProfile> {
    if (json.isBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(json)
        (0 until array.length()).map { CameraProfile.fromJson(array.getJSONObject(it)) }
    }.getOrDefault(emptyList())
}
