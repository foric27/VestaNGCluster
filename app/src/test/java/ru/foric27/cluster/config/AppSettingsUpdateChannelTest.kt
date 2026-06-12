package ru.foric27.cluster.config

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsUpdateChannelTest {

    @Test
    fun `fromPref maps rolling and stable values`() {
        assertEquals(AppSettings.UpdateChannel.ROLLING, AppSettings.UpdateChannel.fromPref("rolling"))
        assertEquals(AppSettings.UpdateChannel.STABLE, AppSettings.UpdateChannel.fromPref("stable"))
    }

    @Test
    fun `fromPref trims and normalizes case`() {
        assertEquals(AppSettings.UpdateChannel.ROLLING, AppSettings.UpdateChannel.fromPref(" ROLLING "))
        assertEquals(AppSettings.UpdateChannel.STABLE, AppSettings.UpdateChannel.fromPref(" Stable "))
    }

    @Test
    fun `unknown values fall back to rolling`() {
        assertEquals(AppSettings.UpdateChannel.ROLLING, AppSettings.UpdateChannel.fromPref(null))
        assertEquals(AppSettings.UpdateChannel.ROLLING, AppSettings.UpdateChannel.fromPref(""))
        assertEquals(AppSettings.UpdateChannel.ROLLING, AppSettings.UpdateChannel.fromPref("beta"))
    }
}
