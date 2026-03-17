package ru.foric27.cluster

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncHandlerPayloadPolicyTest {

    @Test
    fun `time is sent on periodic or time change only`() {
        assertTrue(SyncHandler.shouldIncludeTime(periodic = true, timeChanged = false))
        assertTrue(SyncHandler.shouldIncludeTime(periodic = false, timeChanged = true))
        assertFalse(SyncHandler.shouldIncludeTime(periodic = false, timeChanged = false))
    }

    @Test
    fun `lang is sent on periodic or locale change only`() {
        assertTrue(SyncHandler.shouldIncludeLang(periodic = true, langChanged = false))
        assertTrue(SyncHandler.shouldIncludeLang(periodic = false, langChanged = true))
        assertFalse(SyncHandler.shouldIncludeLang(periodic = false, langChanged = false))
    }

    @Test
    fun `unsupported device languages are mapped to en for cluster`() {
        assertEquals("ru", SyncHandler.mapSupportedClusterLang("ru"))
        assertEquals("en", SyncHandler.mapSupportedClusterLang("en"))
        assertEquals("en", SyncHandler.mapSupportedClusterLang("de"))
        assertEquals("en", SyncHandler.mapSupportedClusterLang("fr"))
        assertEquals("en", SyncHandler.mapSupportedClusterLang(""))
        assertEquals("en", SyncHandler.mapSupportedClusterLang(null))
    }
}
