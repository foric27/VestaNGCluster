package ru.foric27.cluster

import org.junit.Assert.assertEquals
import org.junit.Test

class ClusterModeTest {

    @Test
    fun fromSetting_mapsKnownModes() {
        assertEquals(ClusterMode.TRIP, ClusterMode.fromSetting(0))
        assertEquals(ClusterMode.CLASSIC_NAV, ClusterMode.fromSetting(1))
        assertEquals(ClusterMode.MODERN_NAV, ClusterMode.fromSetting(5))
        assertEquals(ClusterMode.SPORT_MEDIA, ClusterMode.fromSetting(8))
    }

    @Test
    fun fromPref_mapsStableUiValues() {
        assertEquals(ClusterMode.CLASSIC_NAV, ClusterMode.fromPref("nav"))
        assertEquals(ClusterMode.CLASSIC_MEDIA, ClusterMode.fromPref("med"))
        assertEquals(ClusterMode.TRIP, ClusterMode.fromPref("abs"))
        assertEquals(ClusterMode.CLASSIC_NAV, ClusterMode.fromPref("other"))
    }
}
