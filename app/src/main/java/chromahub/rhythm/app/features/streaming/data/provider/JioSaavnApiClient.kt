/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.features.streaming.data.provider

import android.content.Context
import java.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Public JioSaavn API client supporting 320 kbps AAC/MP4 streaming,
 * universal catalog search, and trending/home charts with no authentication required.
 */
class JioSaavnApiClient internal constructor(
    private val okHttpClient: OkHttpClient
) {
    constructor(context: Context) : this(buildHttpClient())

    companion object {
        private const val TAG = "JioSaavnApiClient"
        private const val BASE_URL = "https://www.jiosaavn.com/api.php"
        private const val DES_KEY = "38346591"

        private fun buildHttpClient(): OkHttpClient = UserTrustManager.buildUserTrustingHttpClientBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    // In-memory cache of resolved 320kbps stream URLs: providerId -> direct cdn url
    private val streamUrlCache = ConcurrentHashMap<String, String>()

    fun isConnected(): Boolean = true

    /**
     * Decrypts JioSaavn's DES-ECB encrypted media URL and upgrades quality to 320 kbps.
     */
    fun decryptMediaUrl(encryptedUrl: String): String? {
        if (encryptedUrl.isBlank()) return null
        return try {
            val key = SecretKeySpec(DES_KEY.toByteArray(Charsets.UTF_8), "DES")
            val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding").apply {
                init(Cipher.DECRYPT_MODE, key)
            }
            val decodedBytes = Base64.getDecoder().decode(encryptedUrl)
            val decryptedBytes = cipher.doFinal(decodedBytes)
            val rawUrl = String(decryptedBytes, Charsets.UTF_8)
            rawUrl.replace("_96.mp4", "_320.mp4")
                .replace("_160.mp4", "_320.mp4")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt media URL", e)
            null
        }
    }

    /**
     * Returns the 320kbps CDN stream URL for a given song providerId.
     */
    suspend fun buildStreamUrl(providerId: String, bitrate: Int? = 320): String? = withContext(Dispatchers.IO) {
        streamUrlCache[providerId]?.let { return@withContext it }

        // Fetch song details if not already cached
        val url = BASE_URL.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("__call", "song.getDetails")
            ?.addQueryParameter("pids", providerId)
            ?.addQueryParameter("_format", "json")
            ?.addQueryParameter("_marker", "0")
            ?.build() ?: return@withContext null

        val json = executeGet(url) ?: return@withContext null
        try {
            val songObj = json.optJSONObject(providerId) ?: return@withContext null
            val encryptedUrl = songObj.optString("encrypted_media_url")
            val decrypted = decryptMediaUrl(encryptedUrl)
            if (!decrypted.isNullOrBlank()) {
                streamUrlCache[providerId] = decrypted
            }
            decrypted
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving stream URL for $providerId", e)
            null
        }
    }

    suspend fun searchSongs(query: String, limit: Int = 30): Result<List<ProviderSong>> = withContext(Dispatchers.IO) {
        val url = BASE_URL.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("__call", "search.getResults")
            ?.addQueryParameter("q", query)
            ?.addQueryParameter("_format", "json")
            ?.addQueryParameter("_marker", "0")
            ?.addQueryParameter("p", "1")
            ?.addQueryParameter("n", limit.toString())
            ?.build() ?: return@withContext Result.failure(IOException("Invalid URL"))

        val json = executeGet(url) ?: return@withContext Result.success(emptyList())
        val results = json.optJSONArray("results") ?: JSONArray()
        val songs = parseSongsArray(results)
        Result.success(songs)
    }

    suspend fun searchAlbums(query: String, limit: Int = 20): Result<List<ProviderAlbum>> = withContext(Dispatchers.IO) {
        val url = BASE_URL.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("__call", "search.getAlbumResults")
            ?.addQueryParameter("q", query)
            ?.addQueryParameter("_format", "json")
            ?.addQueryParameter("_marker", "0")
            ?.addQueryParameter("p", "1")
            ?.addQueryParameter("n", limit.toString())
            ?.build() ?: return@withContext Result.failure(IOException("Invalid URL"))

        val json = executeGet(url) ?: return@withContext Result.success(emptyList())
        val results = json.optJSONArray("results") ?: JSONArray()
        val albums = parseAlbumsArray(results)
        Result.success(albums)
    }

    suspend fun searchPlaylists(query: String, limit: Int = 20): Result<List<ProviderPlaylist>> = withContext(Dispatchers.IO) {
        val url = BASE_URL.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("__call", "search.getPlaylistResults")
            ?.addQueryParameter("q", query)
            ?.addQueryParameter("_format", "json")
            ?.addQueryParameter("_marker", "0")
            ?.addQueryParameter("p", "1")
            ?.addQueryParameter("n", limit.toString())
            ?.build() ?: return@withContext Result.failure(IOException("Invalid URL"))

        val json = executeGet(url) ?: return@withContext Result.success(emptyList())
        val results = json.optJSONArray("results") ?: JSONArray()
        val playlists = mutableListOf<ProviderPlaylist>()
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val id = item.optString("listid").ifBlank { item.optString("id") }
            val title = cleanHtml(item.optString("title").ifBlank { item.optString("listname") })
            if (id.isNotBlank() && title.isNotBlank()) {
                playlists.add(
                    ProviderPlaylist(
                        providerId = id,
                        name = title,
                        artworkUrl = cleanArtworkUrl(item.optString("image")),
                        songCount = item.optString("song_count").toIntOrNull() ?: item.optInt("song_count", 0)
                    )
                )
            }
        }
        Result.success(playlists)
    }

    suspend fun searchArtists(query: String, limit: Int = 20): Result<List<ProviderArtist>> = withContext(Dispatchers.IO) {
        val url = BASE_URL.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("__call", "search.getArtistResults")
            ?.addQueryParameter("q", query)
            ?.addQueryParameter("_format", "json")
            ?.addQueryParameter("_marker", "0")
            ?.addQueryParameter("p", "1")
            ?.addQueryParameter("n", limit.toString())
            ?.build() ?: return@withContext Result.failure(IOException("Invalid URL"))

        val json = executeGet(url) ?: return@withContext Result.success(emptyList())
        val results = json.optJSONArray("results") ?: JSONArray()
        val artists = mutableListOf<ProviderArtist>()
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val id = item.optString("artistid").ifBlank { item.optString("id") }
            val name = cleanHtml(item.optString("name").ifBlank { item.optString("title") })
            if (id.isNotBlank() && name.isNotBlank()) {
                artists.add(
                    ProviderArtist(
                        providerId = id,
                        name = name,
                        artworkUrl = cleanArtworkUrl(item.optString("image"))
                    )
                )
            }
        }
        Result.success(artists)
    }

    suspend fun getAlbumSongs(albumId: String): Result<List<ProviderSong>> = withContext(Dispatchers.IO) {
        val url = BASE_URL.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("__call", "content.getAlbumDetails")
            ?.addQueryParameter("albumid", albumId)
            ?.addQueryParameter("_format", "json")
            ?.addQueryParameter("_marker", "0")
            ?.build() ?: return@withContext Result.failure(IOException("Invalid URL"))

        val json = executeGet(url) ?: return@withContext Result.success(emptyList())
        val songsArray = json.optJSONArray("songs") ?: JSONArray()
        Result.success(parseSongsArray(songsArray))
    }

    suspend fun getPlaylistSongs(playlistId: String): Result<List<ProviderSong>> = withContext(Dispatchers.IO) {
        val url = BASE_URL.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("__call", "playlist.getDetails")
            ?.addQueryParameter("listid", playlistId)
            ?.addQueryParameter("_format", "json")
            ?.addQueryParameter("_marker", "0")
            ?.build() ?: return@withContext Result.failure(IOException("Invalid URL"))

        val json = executeGet(url) ?: return@withContext Result.success(emptyList())
        val songsArray = json.optJSONArray("songs") 
            ?: json.optJSONArray("content_list") 
            ?: JSONArray()
        Result.success(parseSongsArray(songsArray))
    }

    suspend fun getNewReleases(limit: Int = 20): Result<List<ProviderAlbum>> = withContext(Dispatchers.IO) {
        val homeJson = getHomepageDataJson() ?: return@withContext Result.success(emptyList())
        val newAlbums = homeJson.optJSONArray("new_albums") ?: JSONArray()
        Result.success(parseAlbumsArray(newAlbums).take(limit))
    }

    suspend fun getFeaturedPlaylists(limit: Int = 20): Result<List<ProviderPlaylist>> = withContext(Dispatchers.IO) {
        val homeJson = getHomepageDataJson() ?: return@withContext Result.success(emptyList())
        val playlists = mutableListOf<ProviderPlaylist>()
        val featured = homeJson.optJSONArray("featured_playlists") ?: JSONArray()
        for (i in 0 until featured.length()) {
            val item = featured.optJSONObject(i) ?: continue
            val id = item.optString("listid").ifBlank { item.optString("id") }
            val title = cleanHtml(item.optString("title").ifBlank { item.optString("listname") })
            if (id.isNotBlank() && title.isNotBlank()) {
                playlists.add(
                    ProviderPlaylist(
                        providerId = id,
                        name = title,
                        artworkUrl = cleanArtworkUrl(item.optString("image")),
                        songCount = item.optString("song_count").toIntOrNull() ?: item.optInt("song_count", 0)
                    )
                )
            }
        }
        Result.success(playlists.take(limit))
    }

    suspend fun getTopCharts(limit: Int = 50): Result<List<ProviderSong>> = withContext(Dispatchers.IO) {
        val homeJson = getHomepageDataJson() ?: return@withContext Result.success(emptyList())
        val charts = homeJson.optJSONArray("charts") ?: JSONArray()
        if (charts.length() > 0) {
            val firstChart = charts.optJSONObject(0)
            val chartId = firstChart?.optString("listid").orEmpty()
            if (chartId.isNotBlank()) {
                val chartSongs = getPlaylistSongs(chartId).getOrNull().orEmpty()
                if (chartSongs.isNotEmpty()) {
                    return@withContext Result.success(chartSongs.take(limit))
                }
            }
        }
        // Fallback: search for top trending songs
        searchSongs("Trending", limit)
    }

    private var cachedHomeJson: JSONObject? = null
    private var homeJsonCacheTime = 0L

    private suspend fun getHomepageDataJson(): JSONObject? {
        val now = System.currentTimeMillis()
        if (cachedHomeJson != null && now - homeJsonCacheTime < 300_000L) {
            return cachedHomeJson
        }
        val url = BASE_URL.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("__call", "content.getHomepageData")
            ?.addQueryParameter("_format", "json")
            ?.addQueryParameter("_marker", "0")
            ?.build() ?: return null

        val json = executeGet(url)
        if (json != null) {
            cachedHomeJson = json
            homeJsonCacheTime = now
        }
        return json
    }

    private fun parseSongsArray(array: JSONArray): List<ProviderSong> {
        val songs = mutableListOf<ProviderSong>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id")
            val title = cleanHtml(item.optString("song").ifBlank { item.optString("title") })
            val artist = cleanHtml(item.optString("singers").ifBlank { item.optString("primary_artists") })
            val album = cleanHtml(item.optString("album"))
            val albumId = item.optString("albumid").ifBlank { null }
            val durationSec = item.optString("duration").toLongOrNull() ?: item.optLong("duration", 0L)
            val artworkUrl = cleanArtworkUrl(item.optString("image"))
            val encryptedUrl = item.optString("encrypted_media_url")

            if (id.isNotBlank() && title.isNotBlank()) {
                if (encryptedUrl.isNotBlank()) {
                    val decrypted = decryptMediaUrl(encryptedUrl)
                    if (!decrypted.isNullOrBlank()) {
                        streamUrlCache[id] = decrypted
                    }
                }
                songs.add(
                    ProviderSong(
                        providerId = id,
                        title = title,
                        artist = artist.ifBlank { "Unknown Artist" },
                        album = album.ifBlank { "Single" },
                        albumId = albumId,
                        durationMs = durationSec * 1000L,
                        artworkUrl = artworkUrl,
                        year = item.optString("year").toIntOrNull(),
                        bitrate = 320,
                        codec = "AAC"
                    )
                )
            }
        }
        return songs
    }

    private fun parseAlbumsArray(array: JSONArray): List<ProviderAlbum> {
        val albums = mutableListOf<ProviderAlbum>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("albumid").ifBlank { item.optString("id") }
            val title = cleanHtml(item.optString("title").ifBlank { item.optString("name") })
            val artist = cleanHtml(item.optString("primary_artists").ifBlank { item.optString("artist") })
            val artworkUrl = cleanArtworkUrl(item.optString("image"))
            val year = item.optString("year").toIntOrNull()
            if (id.isNotBlank() && title.isNotBlank()) {
                albums.add(
                    ProviderAlbum(
                        providerId = id,
                        title = title,
                        artist = artist.ifBlank { "Various Artists" },
                        artworkUrl = artworkUrl,
                        year = year
                    )
                )
            }
        }
        return albums
    }

    private fun cleanArtworkUrl(rawUrl: String?): String? {
        if (rawUrl.isNullOrBlank()) return null
        return rawUrl.replace("150x150.jpg", "500x500.jpg")
            .replace("50x50.jpg", "500x500.jpg")
            .replace("http://", "https://")
    }

    private fun cleanHtml(text: String?): String {
        if (text.isNullOrBlank()) return ""
        return text.replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&#039;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()
    }

    private fun executeGet(url: okhttp3.HttpUrl): JSONObject? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:130.0) Gecko/130.0 Firefox/130.0")
            .build()
        return try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bodyString = response.body?.string() ?: return null
                JSONObject(bodyString)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Request failed for url: $url", e)
            null
        }
    }
}
