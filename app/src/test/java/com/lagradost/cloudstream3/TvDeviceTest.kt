package com.lagradost.cloudstream3

import android.content.res.Configuration
import com.lagradost.cloudstream4.compose.isTelevisionDevice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvDeviceTest {
    @Test
    fun `android tv ui mode is television`() {
        assertTrue(
            isTelevisionDevice(
                uiModeType = Configuration.UI_MODE_TYPE_TELEVISION,
                model = "SHIELD Android TV",
                product = "darcy",
                hasFireTvFeature = false,
            )
        )
    }

    @Test
    fun `fire tv stick 4k is television even without the tv ui mode`() {
        // AFTMM is the 2018 Fire TV Stick 4K. Fire OS often reports a normal ui mode.
        assertTrue(
            isTelevisionDevice(
                uiModeType = Configuration.UI_MODE_TYPE_NORMAL,
                model = "AFTMM",
                product = "AFTMM",
                hasFireTvFeature = false,
            )
        )
        assertTrue(
            isTelevisionDevice(
                uiModeType = Configuration.UI_MODE_TYPE_NORMAL,
                model = "AFTKA",
                product = "aftka",
                hasFireTvFeature = false,
            )
        )
        assertTrue(
            isTelevisionDevice(
                uiModeType = Configuration.UI_MODE_TYPE_NORMAL,
                model = "Fire TV Stick 4K",
                product = "amazon_aftmm",
                hasFireTvFeature = false,
            )
        )
    }

    @Test
    fun `fire tv feature flag is television`() {
        assertTrue(
            isTelevisionDevice(
                uiModeType = Configuration.UI_MODE_TYPE_NORMAL,
                model = "Stick",
                product = "stick",
                hasFireTvFeature = true,
            )
        )
    }

    @Test
    fun `fire tablet stays a phone`() {
        assertFalse(
            isTelevisionDevice(
                uiModeType = Configuration.UI_MODE_TYPE_NORMAL,
                model = "KFTRWI",
                product = "kftrwi",
                hasFireTvFeature = false,
            )
        )
    }

    @Test
    fun `phone model is not television`() {
        assertFalse(
            isTelevisionDevice(
                uiModeType = Configuration.UI_MODE_TYPE_NORMAL,
                model = "Pixel 8",
                product = "shiba",
                hasFireTvFeature = false,
            )
        )
    }
}
