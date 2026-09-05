package com.abk.extension.camera

import org.json.JSONArray
import org.json.JSONObject

enum class CameraId(val sysfs: String, val label: String) {
    FRONT("front", "Front"),
    BACK("back", "Back");

    companion object {
        fun fromSysfs(value: String): CameraId =
            entries.firstOrNull { it.sysfs == value } ?: BACK
    }
}

enum class CameraFormat {
    YUY2,
    MJPEG,
    H264
}

data class Profile(
    val id: String,
    val format: CameraFormat,
    val width: Int,
    val height: Int,
    val fps: Int,
    val stable: Boolean,
    val experimental: Boolean,
    val estimatedMbps: Int,
    val requiresH264Descriptor: Boolean = false,
) {
    val label: String get() = id
}

object ProfileModel {
    val stableProfiles: List<Profile> = listOf(
        Profile("yuy2_640x360", CameraFormat.YUY2, 640, 360, 30, true, false, 110),
        Profile("yuy2_1280x720", CameraFormat.YUY2, 1280, 720, 30, true, false, 442),
        Profile("mjpeg_640x360", CameraFormat.MJPEG, 640, 360, 30, true, false, 25),
        Profile("mjpeg_1280x720", CameraFormat.MJPEG, 1280, 720, 30, true, false, 90),
        Profile("mjpeg_1920x1080_30", CameraFormat.MJPEG, 1920, 1080, 30, true, false, 140),
    )

    val experimentalProfiles: List<Profile> = listOf(
        Profile("mjpeg_1920x1080_60", CameraFormat.MJPEG, 1920, 1080, 60, false, true, 230),
        Profile("mjpeg_3840x2160_15", CameraFormat.MJPEG, 3840, 2160, 15, false, true, 220),
        Profile("mjpeg_3840x2160_30", CameraFormat.MJPEG, 3840, 2160, 30, false, true, 400),
        Profile("h264_1920x1080_60", CameraFormat.H264, 1920, 1080, 60, false, true, 120, requiresH264Descriptor = true),
        Profile("h264_3840x2160_30", CameraFormat.H264, 3840, 2160, 30, false, true, 220, requiresH264Descriptor = true),
        Profile("h264_3840x2160_60", CameraFormat.H264, 3840, 2160, 60, false, true, 420, requiresH264Descriptor = true),
    )

    val allProfiles: List<Profile> = stableProfiles + experimentalProfiles

    private val byId: Map<String, Profile> = allProfiles.associateBy { it.id }

    fun fromId(id: String): Profile = byId[id] ?: byId.getValue("mjpeg_1280x720")

    fun isValid(id: String): Boolean = byId.containsKey(id)

    fun parseSupportedProfiles(raw: String): List<String> =
        raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { it.substringBefore(' ') }
            .filter { isValid(it) }
            .toList()

    fun toJson(): JSONObject = JSONObject()
        .put("schema_version", 1)
        .put("extension_id", "abk_uvc_camera")
        .put("usb", JSONObject()
            .put("high_speed_theoretical_bps", 480_000_000)
            .put("high_speed_practical_budget_bps", 240_000_000))
        .put("profiles", JSONArray().apply {
            allProfiles.forEach { profile ->
                put(JSONObject()
                    .put("id", profile.id)
                    .put("format", profile.format.name)
                    .put("width", profile.width)
                    .put("height", profile.height)
                    .put("fps", profile.fps)
                    .put("stable", profile.stable)
                    .put("experimental", profile.experimental)
                    .put("estimated_mbps", profile.estimatedMbps)
                    .put("requires_h264_uvc_descriptor", profile.requiresH264Descriptor))
            }
        })
}
