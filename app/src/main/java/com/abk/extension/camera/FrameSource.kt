package com.abk.extension.camera

interface FrameSource {
    val id: String
    fun start(profile: Profile, camera: CameraId)
    fun stop()
    fun nextFrame(): ByteArray?
}

class TestPatternFrameSource : FrameSource {
    override val id: String = "test_pattern"
    private val provider = TestPatternProvider()
    private var profile: Profile? = null
    private var camera: CameraId = CameraId.BACK

    override fun start(profile: Profile, camera: CameraId) {
        this.profile = profile
        this.camera = camera
    }

    override fun stop() {
        profile = null
    }

    override fun nextFrame(): ByteArray? {
        val active = profile ?: return null
        return provider.buildYuyv(
            active.width,
            active.height,
            active.id + "/" + camera.sysfs,
            System.currentTimeMillis(),
        )
    }
}
