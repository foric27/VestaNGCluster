package ru.foric27.cluster.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbStoragePathMatcherTest {

    @Test
    fun `USB1 path is recognized`() {
        assertTrue(UsbStoragePathMatcher.isUsbStoragePath("/storage/USB1"))
    }

    @Test
    fun `USB4 path is recognized`() {
        assertTrue(UsbStoragePathMatcher.isUsbStoragePath("/storage/USB4"))
    }

    @Test
    fun `emulated path is rejected`() {
        assertFalse(UsbStoragePathMatcher.isUsbStoragePath("/storage/emulated/0"))
    }

    @Test
    fun `self path is rejected`() {
        assertFalse(UsbStoragePathMatcher.isUsbStoragePath("/storage/self/primary"))
    }

    @Test
    fun `enc_emulated path is rejected`() {
        assertFalse(UsbStoragePathMatcher.isUsbStoragePath("/storage/enc_emulated/0"))
    }

    @Test
    fun `empty string is rejected`() {
        assertFalse(UsbStoragePathMatcher.isUsbStoragePath(""))
    }

    @Test
    fun `blank string is rejected`() {
        assertFalse(UsbStoragePathMatcher.isUsbStoragePath("   "))
    }

    @Test
    fun `sdcard path is rejected`() {
        assertFalse(UsbStoragePathMatcher.isUsbStoragePath("/sdcard"))
    }

    @Test
    fun `storage root is recognized`() {
        assertTrue(UsbStoragePathMatcher.isUsbStoragePath("/storage/"))
    }

    @Test
    fun `subdirectory under USB is recognized`() {
        assertTrue(UsbStoragePathMatcher.isUsbStoragePath("/storage/USB1/subfolder"))
    }
}
