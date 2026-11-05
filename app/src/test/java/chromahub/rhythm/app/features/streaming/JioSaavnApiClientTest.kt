/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.features.streaming

import chromahub.rhythm.app.features.streaming.data.provider.JioSaavnApiClient
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JioSaavnApiClientTest {

    private val client = JioSaavnApiClient(OkHttpClient())

    @Test
    fun testDecryptMediaUrl_upgradesTo320kbps() {
        // Encrypted "https://aac.saavncdn.com/test_96.mp4" using DES key 38346591
        val encrypted = "ID2ieOjCrwfgWvL5sXl4B1ImC5QfbsDy4HMyzx5/Yd4cO7UvWvBrag=="
        val decrypted = client.decryptMediaUrl(encrypted)

        assertNotNull(decrypted)
        assertEquals("https://aac.saavncdn.com/test_320.mp4", decrypted)
    }

    @Test
    fun testIsConnected_returnsTrue() {
        assertTrue(client.isConnected())
    }
}
