/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.features.streaming.data.provider

import android.content.Context
import android.util.Log
import chromahub.rhythm.app.core.domain.model.SourceType
import chromahub.rhythm.app.features.streaming.domain.model.AlbumType
import chromahub.rhythm.app.features.streaming.domain.model.StreamingAlbum
import chromahub.rhythm.app.features.streaming.domain.model.StreamingArtist
import chromahub.rhythm.app.features.streaming.domain.model.StreamingSong
import chromahub.rhythm.app.shared.data.model.LyricsData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Tunes / Monochrome API client for high-res lossless streaming.
 * Connects to Tunes Cloudflare Worker API & Monochrome distributed stream instances.
 */
class MonochromeApiClient(private val context: Context) {

    companion object {
        private const val TAG = "MonochromeApiClient"
        private const val WORKER_TRENDING_URL = "https://tunes-music-app.naolmideksa.workers.dev/api/trending"
        private const val STREAM_BASE_URL = "https://tracks.monochrome.st"
        private val FALLBACK_SEARCH_ENDPOINTS = listOf(
            "https://wolf.qqdl.site",
            "https://maus.qqdl.site",
            "https://vogel.qqdl.site",
            "https://katze.qqdl.site",
            "https://hund.qqdl.site"
        )
    }

    private val okHttpClient: OkHttpClient = UserTrustManager.buildUserTrustingHttpClientBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun isConnected(): Boolean = true

    fun buildCoverArtUrl(coverOrAlbumId: String): String {
        if (coverOrAlbumId.isBlank()) return ""
        if (coverOrAlbumId.startsWith("http://") || coverOrAlbumId.startsWith("https://")) {
            return coverOrAlbumId
        }
        return if (coverOrAlbumId.contains("-")) {
            "https://resources.tidal.com/images/${coverOrAlbumId.replace("-", "/")}/640x640.jpg"
        } else {
            "$STREAM_BASE_URL/proxy/mi/$coverOrAlbumId-1A01.jpg"
        }
    }

    fun buildStreamUrl(trackId: String): String {
        val cleanId = trackId.removePrefix("MONOCHROME::")
        return "$STREAM_BASE_URL/track/$cleanId"
    }

    suspend fun getTrendingSongs(limit: Int = 24): Result<List<StreamingSong>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(WORKER_TRENDING_URL)
                .header("User-Agent", "Mozilla/5.0 Tunes/1.0")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${response.code} from trending"))
                }
                val bodyStr = response.body?.string() ?: return@withContext Result.success(emptyList())
                val json = JSONObject(bodyStr)
                val tracksArray = json.optJSONArray("tracks") ?: return@withContext Result.success(emptyList())

                val songs = mutableListOf<StreamingSong>()
                val targetCount = minOf(tracksArray.length(), limit)
                for (i in 0 until targetCount) {
                    val item = tracksArray.optJSONObject(i) ?: continue
                    val id = item.optString("id")
                    if (id.isBlank()) continue

                    val title = item.optString("title", "Unknown Title")
                    val artist = item.optString("artist", "Unknown Artist")
                    val album = item.optString("album", "")
                    val albumId = item.optString("albumId", "")
                    val artwork = if (albumId.isNotBlank()) buildCoverArtUrl(albumId) else null

                    songs.add(
                        StreamingSong(
                            id = "MONOCHROME::$id",
                            title = title,
                            artist = artist,
                            album = album,
                            duration = 210000L,
                            artworkUri = artwork,
                            sourceType = SourceType.MONOCHROME,
                            streamingUrl = buildStreamUrl(id),
                            previewUrl = null,
                            albumId = albumId.takeIf { it.isNotBlank() },
                            isPlayable = true,
                            codec = "flac"
                        )
                    )
                }
                Result.success(songs)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch trending songs: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun getTrendingAlbums(limit: Int = 24): Result<List<StreamingAlbum>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(WORKER_TRENDING_URL)
                .header("User-Agent", "Mozilla/5.0 Tunes/1.0")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${response.code} from trending"))
                }
                val bodyStr = response.body?.string() ?: return@withContext Result.success(emptyList())
                val json = JSONObject(bodyStr)
                val albumsArray = json.optJSONArray("albums") ?: return@withContext Result.success(emptyList())

                val albums = mutableListOf<StreamingAlbum>()
                val targetCount = minOf(albumsArray.length(), limit)
                for (i in 0 until targetCount) {
                    val item = albumsArray.optJSONObject(i) ?: continue
                    val id = item.optString("id")
                    if (id.isBlank()) continue

                    val title = item.optString("title", "Unknown Album")
                    val artist = item.optString("artist", "Unknown Artist")
                    val artwork = buildCoverArtUrl(id)

                    albums.add(
                        StreamingAlbum(
                            id = "MONOCHROME::album::$id",
                            title = title,
                            artist = artist,
                            artworkUri = artwork,
                            songCount = item.optInt("count", 1),
                            year = null,
                            sourceType = SourceType.MONOCHROME,
                            albumType = AlbumType.ALBUM
                        )
                    )
                }
                Result.success(albums)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch trending albums: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun searchSongs(query: String, limit: Int = 25): Result<List<StreamingSong>> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext Result.success(emptyList())

        val encoded = URLEncoder.encode(cleanQuery, "UTF-8")

        // 1. Direct search on tracks.monochrome.st (fast unencrypted FLAC catalog)
        try {
            val directUrl = "$STREAM_BASE_URL/search?q=$encoded"
            val req = Request.Builder()
                .url(directUrl)
                .header("User-Agent", "Mozilla/5.0 Tunes/1.0")
                .build()

            okHttpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val bodyStr = resp.body?.string()
                    if (!bodyStr.isNullOrBlank()) {
                        val json = JSONObject(bodyStr)
                        val tracksArray = json.optJSONArray("tracks")
                        if (tracksArray != null && tracksArray.length() > 0) {
                            val songs = mutableListOf<StreamingSong>()
                            val count = minOf(tracksArray.length(), limit)
                            for (i in 0 until count) {
                                val item = tracksArray.optJSONObject(i) ?: continue
                                val id = item.optString("trackId", item.optString("id"))
                                if (id.isBlank()) continue

                                val title = item.optString("title", "Unknown Title")
                                val artistNames = item.optJSONArray("artistNames")
                                val artist = if (artistNames != null && artistNames.length() > 0) {
                                    (0 until artistNames.length()).map { artistNames.optString(it) }.joinToString(", ")
                                } else {
                                    item.optString("artist", "Unknown Artist")
                                }
                                val artwork = item.optString("artwork").takeIf { it.isNotBlank() }
                                val durationMs = item.optLong("duration", 210000L)
                                val releaseId = item.optString("releaseId").takeIf { it.isNotBlank() }

                                songs.add(
                                    StreamingSong(
                                        id = "MONOCHROME::$id",
                                        title = title,
                                        artist = artist,
                                        album = "",
                                        duration = durationMs,
                                        artworkUri = artwork,
                                        sourceType = SourceType.MONOCHROME,
                                        streamingUrl = buildStreamUrl(id),
                                        previewUrl = null,
                                        albumId = releaseId,
                                        isPlayable = true,
                                        codec = "flac"
                                    )
                                )
                            }
                            if (songs.isNotEmpty()) {
                                return@withContext Result.success(songs)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Direct search failed, falling back to distributed nodes: ${e.message}")
        }

        // 2. Fallback to distributed nodes
        for (baseUrl in FALLBACK_SEARCH_ENDPOINTS) {
            try {
                val url = "$baseUrl/search/?s=$encoded"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 Tunes/1.0")
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use

                    val bodyStr = response.body?.string() ?: return@use
                    val json = JSONObject(bodyStr)
                    val items = json.optJSONArray("items") ?: return@use

                    val songs = mutableListOf<StreamingSong>()
                    val count = minOf(items.length(), limit)
                    for (i in 0 until count) {
                        val item = items.optJSONObject(i) ?: continue
                        val id = item.optString("id")
                        if (id.isBlank()) continue

                        val title = item.optString("title", "Unknown Title")
                        val artistObj = item.optJSONObject("artist")
                        val artistName = artistObj?.optString("name") ?: "Unknown Artist"
                        val albumObj = item.optJSONObject("album")
                        val albumTitle = albumObj?.optString("title") ?: ""
                        val cover = albumObj?.optString("cover")
                        val artwork = cover?.let { buildCoverArtUrl(it) }
                        val durationSec = item.optDouble("duration", 210.0)

                        songs.add(
                            StreamingSong(
                                id = "MONOCHROME::$id",
                                title = title,
                                artist = artistName,
                                album = albumTitle,
                                duration = (durationSec * 1000).toLong(),
                                artworkUri = artwork,
                                sourceType = SourceType.MONOCHROME,
                                streamingUrl = buildStreamUrl(id),
                                previewUrl = null,
                                isPlayable = true,
                                codec = "flac"
                            )
                        )
                    }
                    if (songs.isNotEmpty()) {
                        return@withContext Result.success(songs)
                    }
                }
            } catch (_: Exception) {
                continue
            }
        }
        Result.success(emptyList())
    }

    suspend fun getAlbumSongs(releaseId: String): Result<List<StreamingSong>> = withContext(Dispatchers.IO) {
        val cleanId = releaseId.removePrefix("MONOCHROME::album::").removePrefix("MONOCHROME::")
        try {
            val url = "$STREAM_BASE_URL/releases/$cleanId"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 Tunes/1.0")
                .build()

            okHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext Result.failure(Exception("HTTP ${resp.code} from release"))
                val bodyStr = resp.body?.string() ?: return@withContext Result.success(emptyList())
                val json = JSONObject(bodyStr)
                val tracks = json.optJSONArray("tracks") ?: return@withContext Result.success(emptyList())
                val albumTitle = json.optString("title", "")
                val artwork = json.optString("artwork")

                val songs = mutableListOf<StreamingSong>()
                for (i in 0 until tracks.length()) {
                    val item = tracks.optJSONObject(i) ?: continue
                    val id = item.optString("trackId", item.optString("id"))
                    if (id.isBlank()) continue

                    val title = item.optString("title", "Track ${i + 1}")
                    val artistNames = item.optJSONArray("artistNames")
                    val artist = if (artistNames != null && artistNames.length() > 0) {
                        (0 until artistNames.length()).map { artistNames.optString(it) }.joinToString(", ")
                    } else {
                        item.optString("artist", "Unknown Artist")
                    }
                    val durationMs = item.optLong("duration", 210000L)

                    songs.add(
                        StreamingSong(
                            id = "MONOCHROME::$id",
                            title = title,
                            artist = artist,
                            album = albumTitle,
                            duration = durationMs,
                            artworkUri = artwork.takeIf { it.isNotBlank() },
                            sourceType = SourceType.MONOCHROME,
                            streamingUrl = buildStreamUrl(id),
                            previewUrl = null,
                            albumId = cleanId,
                            trackNumber = i + 1,
                            isPlayable = true,
                            codec = "flac"
                        )
                    )
                }
                Result.success(songs)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get album tracks: ${e.message}")
            Result.failure(e)
        }
    }
}
