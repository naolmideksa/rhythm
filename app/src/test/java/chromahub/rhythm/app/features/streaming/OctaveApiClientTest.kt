/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.features.streaming

import chromahub.rhythm.app.features.streaming.data.provider.OctaveApiClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OctaveApiClientTest {

    private val client = OctaveApiClient(OkHttpClient())

    @Test
    fun testIsConnected_returnsTrue() {
        assertTrue(client.isConnected())
    }

    @Test
    fun testSetAccountKey_setsTokenWhenTokenProvided() = runBlocking {
        client.setAccountKey("octk_testtoken123")
        val streamUrl = client.buildStreamUrl("12345", 320)
        assertNotNull(streamUrl)
        assertTrue(streamUrl!!.contains("k=octk_testtoken123"))
        assertTrue(streamUrl.contains("audio/320?track=12345"))
    }

    @Test
    fun testQualitySelection() = runBlocking {
        client.setAccountKey("octk_test")
        val losslessUrl = client.buildStreamUrl("100", 1411)
        val lowUrl = client.buildStreamUrl("101", 128)
        assertTrue(losslessUrl!!.contains("/audio/lossless?track=100"))
        assertTrue(lowUrl!!.contains("/audio/128?track=101"))
    }

    @Test
    fun testSoundCloudAudioResolutionWhenNoToken() = runBlocking {
        val streamUrl = client.buildStreamUrl(
            providerId = "681009642",
            bitrate = 320,
            title = "IGOR'S THEME",
            artist = "Tyler, The Creator",
            durationSec = 200
        )
        assertNotNull(streamUrl)
        assertTrue(
            streamUrl!!.contains("soundcloud.cloud") ||
            streamUrl.contains("sndcdn.com") ||
            streamUrl.contains("dzcdn.net")
        )
    }
}
