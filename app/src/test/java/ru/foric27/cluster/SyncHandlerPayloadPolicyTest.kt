package ru.foric27.cluster

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
}
