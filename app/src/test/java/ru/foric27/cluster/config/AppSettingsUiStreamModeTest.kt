package ru.foric27.cluster.config

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsUiStreamModeTest {

    @Test
    fun `fromPref maps nav med abs to expected ui modes`() {
        assertEquals(AppSettings.UiStreamMode.NAV, AppSettings.UiStreamMode.fromPref("nav"))
        assertEquals(AppSettings.UiStreamMode.MED, AppSettings.UiStreamMode.fromPref("med"))
        assertEquals(AppSettings.UiStreamMode.ABS, AppSettings.UiStreamMode.fromPref("abs"))
    }

    @Test
    fun `fromPref trims and normalizes case`() {
        assertEquals(AppSettings.UiStreamMode.NAV, AppSettings.UiStreamMode.fromPref(" NAV "))
        assertEquals(AppSettings.UiStreamMode.MED, AppSettings.UiStreamMode.fromPref(" Med "))
        assertEquals(AppSettings.UiStreamMode.ABS, AppSettings.UiStreamMode.fromPref(" ABS "))
    }

    @Test
    fun `fromSetting maps media values to med and trip values to abs`() {
        assertEquals(AppSettings.UiStreamMode.NAV, AppSettings.UiStreamMode.fromSetting(1))
        assertEquals(AppSettings.UiStreamMode.NAV, AppSettings.UiStreamMode.fromSetting(3))
        assertEquals(AppSettings.UiStreamMode.NAV, AppSettings.UiStreamMode.fromSetting(5))
        assertEquals(AppSettings.UiStreamMode.NAV, AppSettings.UiStreamMode.fromSetting(7))
        assertEquals(AppSettings.UiStreamMode.MED, AppSettings.UiStreamMode.fromSetting(2))
        assertEquals(AppSettings.UiStreamMode.MED, AppSettings.UiStreamMode.fromSetting(4))
        assertEquals(AppSettings.UiStreamMode.MED, AppSettings.UiStreamMode.fromSetting(6))
        assertEquals(AppSettings.UiStreamMode.MED, AppSettings.UiStreamMode.fromSetting(8))
        assertEquals(AppSettings.UiStreamMode.ABS, AppSettings.UiStreamMode.fromSetting(0))
        assertEquals(AppSettings.UiStreamMode.ABS, AppSettings.UiStreamMode.fromSetting(9))
    }

    @Test
    fun `unknown values fall back safely`() {
        assertEquals(AppSettings.UiStreamMode.NAV, AppSettings.UiStreamMode.fromPref(null))
        assertEquals(AppSettings.UiStreamMode.NAV, AppSettings.UiStreamMode.fromPref(""))
        assertEquals(AppSettings.UiStreamMode.NAV, AppSettings.UiStreamMode.fromPref("unknown"))
        assertEquals(AppSettings.UiStreamMode.NAV, AppSettings.UiStreamMode.fromSetting(999))
    }
}
