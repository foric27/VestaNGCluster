package ru.foric27.cluster

import android.media.MediaCodecInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoEncoderTest {

    @Test
    fun `buildCodecConfigCandidates returns exact match when profile and level supported`() {
        val capabilities = EncoderCapabilitySnapshot(
            codecName = "testEncoder",
            supportsCbr = true,
            supportedProfileLevels = listOf(
                CodecProfileLevelSupport(
                    profile = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                    level = MediaCodecInfo.CodecProfileLevel.AVCLevel41,
                ),
            ),
        )

        val candidates = buildCodecConfigCandidates(
            capabilities = capabilities,
            requestedProfile = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
            requestedLevel = MediaCodecInfo.CodecProfileLevel.AVCLevel41,
        )

        assertTrue(candidates.isNotEmpty())
        val first = candidates.first()
        assertEquals("requested_profile_level", first.label)
        assertEquals(MediaCodecInfo.CodecProfileLevel.AVCProfileHigh, first.profile)
        assertEquals(MediaCodecInfo.CodecProfileLevel.AVCLevel41, first.level)
        assertNotNull(first.bitrateMode)
    }

    @Test
    fun `buildCodecConfigCandidates falls back to supported level when requested level not supported`() {
        val capabilities = EncoderCapabilitySnapshot(
            codecName = "testEncoder",
            supportsCbr = false,
            supportedProfileLevels = listOf(
                CodecProfileLevelSupport(
                    profile = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                    level = MediaCodecInfo.CodecProfileLevel.AVCLevel31,
                ),
            ),
        )

        val candidates = buildCodecConfigCandidates(
            capabilities = capabilities,
            requestedProfile = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
            requestedLevel = MediaCodecInfo.CodecProfileLevel.AVCLevel41,
        )

        assertTrue(candidates.isNotEmpty())
        val first = candidates.first()
        assertEquals("requested_profile_supported_level", first.label)
        assertEquals(MediaCodecInfo.CodecProfileLevel.AVCProfileHigh, first.profile)
        assertEquals(MediaCodecInfo.CodecProfileLevel.AVCLevel31, first.level)
        assertNull(first.bitrateMode)
    }

    @Test
    fun `buildCodecConfigCandidates includes fallback profiles`() {
        val capabilities = EncoderCapabilitySnapshot(
            codecName = "testEncoder",
            supportsCbr = true,
            supportedProfileLevels = listOf(
                CodecProfileLevelSupport(
                    profile = MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
                    level = MediaCodecInfo.CodecProfileLevel.AVCLevel31,
                ),
            ),
        )

        val candidates = buildCodecConfigCandidates(
            capabilities = capabilities,
            requestedProfile = MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedHigh,
            requestedLevel = MediaCodecInfo.CodecProfileLevel.AVCLevel41,
        )

        assertTrue(candidates.size >= 2)
        val fallbackCandidate = candidates.firstOrNull { it.label.startsWith("fallback_profile_") }
        assertNotNull(fallbackCandidate)
        assertEquals(MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline, fallbackCandidate?.profile)
    }

    @Test
    fun `buildCodecConfigCandidates always includes generic fallback`() {
        val capabilities = EncoderCapabilitySnapshot(
            codecName = "testEncoder",
            supportsCbr = false,
            supportedProfileLevels = emptyList(),
        )

        val candidates = buildCodecConfigCandidates(
            capabilities = capabilities,
            requestedProfile = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
            requestedLevel = MediaCodecInfo.CodecProfileLevel.AVCLevel41,
        )

        assertTrue(candidates.isNotEmpty())
        val genericCandidate = candidates.last()
        assertEquals("generic_surface_avc", genericCandidate.label)
        assertNull(genericCandidate.profile)
        assertNull(genericCandidate.level)
    }

    @Test
    fun `buildCodecConfigCandidates sets bitrateMode when CBR supported`() {
        val capabilities = EncoderCapabilitySnapshot(
            codecName = "testEncoder",
            supportsCbr = true,
            supportedProfileLevels = emptyList(),
        )

        val candidates = buildCodecConfigCandidates(
            capabilities = capabilities,
            requestedProfile = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
            requestedLevel = MediaCodecInfo.CodecProfileLevel.AVCLevel41,
        )

        candidates.forEach { candidate ->
            assertNotNull("bitrateMode should be set when CBR is supported", candidate.bitrateMode)
        }
    }

    @Test
    fun `buildCodecConfigCandidates sets null bitrateMode when CBR not supported`() {
        val capabilities = EncoderCapabilitySnapshot(
            codecName = "testEncoder",
            supportsCbr = false,
            supportedProfileLevels = emptyList(),
        )

        val candidates = buildCodecConfigCandidates(
            capabilities = capabilities,
            requestedProfile = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
            requestedLevel = MediaCodecInfo.CodecProfileLevel.AVCLevel41,
        )

        candidates.forEach { candidate ->
            assertNull("bitrateMode should be null when CBR is not supported", candidate.bitrateMode)
        }
    }
}
