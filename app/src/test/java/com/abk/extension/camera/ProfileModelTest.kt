package com.abk.extension.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileModelTest {
    @Test
    fun stableProfilesAreNotExperimental() {
        assertTrue(ProfileModel.stableProfiles.all { it.stable && !it.experimental })
    }

    @Test
    fun highRateRawYuy2IsNeverDeclared() {
        val forbidden = setOf(
            "yuy2_1920x1080_60",
            "yuy2_3840x2160_15",
            "yuy2_3840x2160_30",
            "yuy2_3840x2160_60",
        )
        assertTrue(forbidden.none { ProfileModel.isValid(it) })
    }

    @Test
    fun experimentalProfilesAreMarkedExperimental() {
        assertTrue(ProfileModel.experimentalProfiles.all { it.experimental && !it.stable })
    }

    @Test
    fun bandwidthMathMatchesUsb2Boundary() {
        val yuy2_1080p60 = 1920L * 1080 * 2 * 60 * 8
        val yuy2_4k60 = 3840L * 2160 * 2 * 60 * 8
        val budget = 480_000_000L
        assertTrue(yuy2_1080p60 > budget)
        assertTrue(yuy2_4k60 > budget)
    }

    @Test
    fun fromIdFallsBackForUnknown() {
        assertEquals("mjpeg_1280x720", ProfileModel.fromId("does_not_exist").id)
    }

}
