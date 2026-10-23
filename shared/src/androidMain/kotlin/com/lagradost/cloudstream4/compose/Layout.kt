package com.lagradost.cloudstream4.compose

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import com.lagradost.cloudstream4.compose.DeviceLayout.Companion.LocalLayout

@JvmInline
@Immutable
value class DeviceLayout(private val value: Int) {
    infix fun or(other: DeviceLayout) =
        DeviceLayout(value or other.value)

    internal infix fun and(other: DeviceLayout) = (value and other.value) != 0

    companion object {
        val LocalLayout: ProvidableCompositionLocal<DeviceLayout> = staticCompositionLocalOf { DeviceLayout(-1) }
        fun isAutoTv(context: Context): Boolean {
            val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            val hasFireTvFeature = try {
                context.packageManager.hasSystemFeature("amazon.hardware.fire_tv")
            } catch (_: Throwable) {
                false
            }
            return isTelevisionDevice(
                uiModeType = uiModeManager?.currentModeType
                    ?: Configuration.UI_MODE_TYPE_UNDEFINED,
                model = Build.MODEL,
                product = Build.PRODUCT,
                hasFireTvFeature = hasFireTvFeature,
            )
        }
        internal fun layoutToFlag(context : Context, from : Int): DeviceLayout {
            return when(from) {
                -1 -> if (isAutoTv(context)) TV else PHONE
                0 -> PHONE
                1 -> TV
                2 -> EMULATOR
                else -> PHONE
            }
        }
    }
}

val PHONE = DeviceLayout(0b00001)
val TV = DeviceLayout(0b00010)
val EMULATOR = DeviceLayout(0b00100)

/**
 * True when the device should use the television layout.
 *
 * Fire TV Stick 4K (AFTMM), Stick 4K Max (AFTKA / AFTSS) and other Fire OS
 * TV boards often do not report [Configuration.UI_MODE_TYPE_TELEVISION].
 * They do expose `amazon.hardware.fire_tv`, and their model or product
 * string starts with "AFT". Friendly names such as "Fire TV Stick 4K" are
 * matched too. Fire tablets are Amazon devices whose models start with "KF",
 * so they stay on the phone layout.
 */
fun isTelevisionDevice(
    uiModeType: Int,
    model: String?,
    product: String?,
    hasFireTvFeature: Boolean,
): Boolean {
    if (uiModeType == Configuration.UI_MODE_TYPE_TELEVISION || hasFireTvFeature) return true
    return sequenceOf(model, product).filterNotNull().any { it.isFireTvOrAndroidTvName() }
}

private fun String.isFireTvOrAndroidTvName(): Boolean {
    val value = lowercase()
    if (
        value.contains("firestick") ||
        value.contains("fire tv") ||
        value.contains("firetv") ||
        value.contains("chromecast")
    ) {
        return true
    }
    // Board ids: AFTMM, AFTKA, AFTKM, AFTSSS, AFTT, … "after" is not one of them.
    return value.split(Regex("[^a-z0-9]+")).any { token ->
        token.startsWith("aft") && token != "after"
    }
}

@Composable
fun isLayout(flags: DeviceLayout) : Boolean = LocalLayout.current and flags
