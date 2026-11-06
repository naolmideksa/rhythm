/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.features.streaming.data.provider

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * Client for Octave Streaming (https://api.octavestreaming.com),
 * providing global music catalog access (Spotify & Deezer libraries),
 * official studio releases, 320 kbps streaming, search, and charts.
 */
class OctaveApiClient internal constructor(
    private val okHttpClient: OkHttpClient
) {
    constructor(context: Context) : this(buildHttpClient())

    companion object {
        private const val TAG = "OctaveApiClient"
        private const val BASE_URL = "https://api.octavestreaming.com"
        private const val ORIGIN = "https://music.octavestreaming.com"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

        private fun buildHttpClient(): OkHttpClient = UserTrustManager.buildUserTrustingHttpClientBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    // Default account key or token if available
    @Volatile
    private var accountKey: String = "octv_0a967319b4c1e1dcc024044e88ebf47235fa343b859d28d3"

    @Volatile
    private var cachedPlaybackToken: String? = null

    @Volatile
    private var tokenExpiresAtEpochMs: Long = 0L

    // In-memory cache of stream and preview URLs
    private val streamUrlCache = ConcurrentHashMap<String, String>()
    private val previewUrlCache = ConcurrentHashMap<String, String>()

    private data class TrackMeta(val title: String, val artist: String, val durationSec: Long)
    private val trackMetaCache = ConcurrentHashMap<String, TrackMeta>()
    private val soundCloudClientId = AtomicReference("39AVoiZVrdvJEMFLH8T0c5WGshQaAPRJ")

    fun isConnected(): Boolean = true

    fun setAccountKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.startsWith("octk_")) {
            cachedPlaybackToken = trimmed
            tokenExpiresAtEpochMs = System.currentTimeMillis() + 3_600_000L
        } else if (trimmed.isNotBlank()) {
            accountKey = trimmed
            cachedPlaybackToken = null
            tokenExpiresAtEpochMs = 0L
        }
    }

    /**
     * Obtains or refreshes an active Octave playback token.
     */
    suspend fun ensurePlaybackToken(): String? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!cachedPlaybackToken.isNullOrBlank() && now < tokenExpiresAtEpochMs - 60_000L) {
            return@withContext cachedPlaybackToken
        }

        val url = "$BASE_URL/api/playback-token".toHttpUrlOrNull() ?: return@withContext null
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Origin", ORIGIN)
            .header("Referer", "$ORIGIN/")

        if (accountKey.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer $accountKey")
        }

        try {
            okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (!body.isNullOrBlank()) {
                        val json = JSONObject(body)
                        val token = json.optString("token")
                        val expiresInSec = json.optLong("expiresIn", 3600L)
                        if (token.isNotBlank()) {
                            cachedPlaybackToken = token
                            tokenExpiresAtEpochMs = now + (expiresInSec * 1000L)
                            return@withContext token
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh Octave playback token", e)
        }
        cachedPlaybackToken
    }

    /**
     * Resolves audio stream URL for a given track ID.
     * If an explicit playback token is configured, streams 320 kbps from Octave.
     * Otherwise, resolves the full ungated audio stream from SoundCloud using track metadata,
     * falling back to the direct 30s preview URL if unresolvable.
     */
    suspend fun buildStreamUrl(
        providerId: String,
        bitrate: Int? = 320,
        title: String? = null,
        artist: String? = null,
        durationSec: Long? = null
    ): String? = withContext(Dispatchers.IO) {
        streamUrlCache[providerId]?.let { return@withContext it }

        // If an explicit playback token was set, use Octave's direct audio endpoint
        if (!cachedPlaybackToken.isNullOrBlank()) {
            val quality = when {
                bitrate != null && bitrate >= 1000 -> "lossless"
                bitrate != null && bitrate <= 128 -> "128"
                else -> "320"
            }
            val streamUrl = "$BASE_URL/audio/$quality?track=${providerId}&k=$cachedPlaybackToken"
            streamUrlCache[providerId] = streamUrl
            return@withContext streamUrl
        }

        // Ungated resolution: stream full audio via SoundCloud using track metadata
        val resolvedTitle = title?.ifBlank { null } ?: trackMetaCache[providerId]?.title
        val resolvedArtist = artist?.ifBlank { null } ?: trackMetaCache[providerId]?.artist
        val resolvedDuration = durationSec ?: trackMetaCache[providerId]?.durationSec

        if (!resolvedTitle.isNullOrBlank()) {
            val scUrl = resolveSoundCloudAudioStream(resolvedArtist.orEmpty(), resolvedTitle, resolvedDuration)
            if (!scUrl.isNullOrBlank()) {
                streamUrlCache[providerId] = scUrl
                return@withContext scUrl
            }
        }

        // Fallback: direct preview audio stream
        previewUrlCache[providerId]?.let { return@withContext it }
        val preview = fetchTrackPreviewUrl(providerId)
        if (!preview.isNullOrBlank()) {
            previewUrlCache[providerId] = preview
            return@withContext preview
        }

        null
    }

    /**
     * Resolves an ungated full audio stream from SoundCloud using track metadata.
     */
    private fun resolveSoundCloudAudioStream(artist: String, title: String, durationSec: Long?): String? {
        val query = if (artist.isNotBlank()) "$artist $title" else title
        val encodedQuery = try {
            URLEncoder.encode(query, "UTF-8")
        } catch (e: Exception) {
            query.replace(" ", "%20")
        }

        var clientId = soundCloudClientId.get()
        var searchUrl = "https://api-v2.soundcloud.com/search/tracks?q=$encodedQuery&client_id=$clientId&limit=10".toHttpUrlOrNull()
            ?: return null

        var responseJson = executeGet(searchUrl)
        if (responseJson == null) {
            refreshSoundCloudClientId()
            clientId = soundCloudClientId.get()
            searchUrl = "https://api-v2.soundcloud.com/search/tracks?q=$encodedQuery&client_id=$clientId&limit=10".toHttpUrlOrNull()
                ?: return null
            responseJson = executeGet(searchUrl) ?: return null
        }

        val collection = responseJson.optJSONArray("collection") ?: return null
        if (collection.length() == 0) return null

        val candidates = mutableListOf<JSONObject>()
        for (i in 0 until collection.length()) {
            val item = collection.optJSONObject(i) ?: continue
            val durMs = item.optLong("duration", 0L)
            if (durMs > 45_000L) {
                candidates.add(item)
            }
        }

        val pool = if (candidates.isNotEmpty()) candidates else {
            val all = mutableListOf<JSONObject>()
            for (i in 0 until collection.length()) {
                collection.optJSONObject(i)?.let { all.add(it) }
            }
            all
        }
        if (pool.isEmpty()) return null

        val targetDurationSec = durationSec ?: 0L
        val bestItem = if (targetDurationSec > 0L) {
            pool.minByOrNull { item ->
                val itemSec = item.optLong("duration", 0L) / 1000L
                abs(itemSec - targetDurationSec)
            } ?: pool.first()
        } else {
            pool.first()
        }

        val media = bestItem.optJSONObject("media") ?: return null
        val transcodings = media.optJSONArray("transcodings") ?: return null

        var streamResolveUrl: String? = null
        for (i in 0 until transcodings.length()) {
            val t = transcodings.optJSONObject(i) ?: continue
            val format = t.optJSONObject("format") ?: continue
            val protocol = format.optString("protocol")
            if (protocol.equals("progressive", ignoreCase = true)) {
                streamResolveUrl = t.optString("url")
                break
            }
        }
        if (streamResolveUrl.isNullOrBlank()) {
            for (i in 0 until transcodings.length()) {
                val t = transcodings.optJSONObject(i) ?: continue
                val format = t.optJSONObject("format") ?: continue
                val protocol = format.optString("protocol")
                if (protocol.equals("hls", ignoreCase = true)) {
                    streamResolveUrl = t.optString("url")
                    break
                }
            }
        }

        if (streamResolveUrl.isNullOrBlank()) return null

        val resolveHttpUrl = "$streamResolveUrl?client_id=$clientId".toHttpUrlOrNull() ?: return null
        val streamData = executeGet(resolveHttpUrl) ?: return null
        return streamData.optString("url").takeIf { it.isNotBlank() }
    }

    private fun refreshSoundCloudClientId() {
        try {
            val request = Request.Builder()
                .url("https://soundcloud.com")
                .header("User-Agent", USER_AGENT)
                .build()
            val html = okHttpClient.newCall(request).execute().use { it.body?.string().orEmpty() }
            val scriptRegex = Regex("""https://a-v2\.sndcdn\.com/assets/[^"]+\.js""")
            val scripts = scriptRegex.findAll(html).map { it.value }.toList()
            for (script in scripts.reversed().take(5)) {
                val scriptReq = Request.Builder().url(script).header("User-Agent", USER_AGENT).build()
                val js = okHttpClient.newCall(scriptReq).execute().use { it.body?.string().orEmpty() }
                val match = Regex("""client_id["=: ]+([a-zA-Z0-9]{32})""").find(js)
                if (match != null) {
                    val newId = match.groupValues[1]
                    soundCloudClientId.set(newId)
                    break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to dynamically refresh SoundCloud client ID", e)
        }
    }

    suspend fun searchSongs(query: String, limit: Int = 30): Result<List<ProviderSong>> = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/api/search/tracks".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("query", query)
            ?.addQueryParameter("limit", limit.toString())
            ?.build() ?: return@withContext Result.failure(IOException("Invalid URL"))

        val json = executeGet(url) ?: return@withContext Result.success(emptyList())
        val results = json.optJSONArray("results") ?: JSONArray()
        Result.success(parseSongsArray(results))
    }

    suspend fun searchAlbums(query: String, limit: Int = 20): Result<List<ProviderAlbum>> = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/api/search/albums".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("query", query)
            ?.addQueryParameter("limit", limit.toString())
            ?.build() ?: return@withContext Result.failure(IOException("Invalid URL"))

        val json = executeGet(url) ?: return@withContext Result.success(emptyList())
        val results = json.optJSONArray("results") ?: JSONArray()
        Result.success(parseAlbumsArray(results))
    }

    suspend fun searchArtists(query: String, limit: Int = 20): Result<List<ProviderArtist>> = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/api/search/artists".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("query", query)
            ?.addQueryParameter("limit", limit.toString())
            ?.build() ?: return@withContext Result.failure(IOException("Invalid URL"))

        val json = executeGet(url) ?: return@withContext Result.success(emptyList())
        val results = json.optJSONArray("results") ?: JSONArray()
        Result.success(parseArtistsArray(results))
    }

    suspend fun searchPlaylists(query: String, limit: Int = 20): Result<List<ProviderPlaylist>> = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/api/search/page".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("query", query)
            ?.addQueryParameter("limit", limit.toString())
            ?.build() ?: return@withContext Result.failure(IOException("Invalid URL"))

        val json = executeGet(url) ?: return@withContext Result.success(emptyList())
        val results = json.optJSONArray("playlists") ?: JSONArray()
        val playlists = mutableListOf<ProviderPlaylist>()
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val id = item.optString("id")
            val title = cleanHtml(item.optString("title"))
            if (id.isNotBlank() && title.isNotBlank()) {
                playlists.add(
                    ProviderPlaylist(
                        providerId = id,
                        name = title,
                        artworkUrl = item.optString("image").ifBlank { null },
                        songCount = item.optInt("nbTracks", 0)
                    )
                )
            }
        }
        Result.success(playlists)
    }

    suspend fun getTopCharts(limit: Int = 50): Result<List<ProviderSong>> = withContext(Dispatchers.IO) {
        val chartsJson = getChartsJson() ?: return@withContext Result.success(emptyList())
        val tracks = chartsJson.optJSONArray("tracks") ?: JSONArray()
        Result.success(parseSongsArray(tracks).take(limit))
    }

    suspend fun getNewReleases(limit: Int = 20): Result<List<ProviderAlbum>> = withContext(Dispatchers.IO) {
        val chartsJson = getChartsJson() ?: return@withContext Result.success(emptyList())
        val albums = chartsJson.optJSONArray("albums") ?: JSONArray()
        Result.success(parseAlbumsArray(albums).take(limit))
    }

    suspend fun getFeaturedPlaylists(limit: Int = 20): Result<List<ProviderPlaylist>> = withContext(Dispatchers.IO) {
        val chartsJson = getChartsJson() ?: return@withContext Result.success(emptyList())
        val playlistsArray = chartsJson.optJSONArray("playlists") ?: JSONArray()
        val playlists = mutableListOf<ProviderPlaylist>()
        for (i in 0 until playlistsArray.length()) {
            val item = playlistsArray.optJSONObject(i) ?: continue
            val id = item.optString("id")
            val title = cleanHtml(item.optString("title"))
            if (id.isNotBlank() && title.isNotBlank()) {
                playlists.add(
                    ProviderPlaylist(
                        providerId = id,
                        name = title,
                        artworkUrl = item.optString("image").ifBlank { null },
                        songCount = item.optInt("nbTracks", 0)
                    )
                )
            }
        }
        Result.success(playlists.take(limit))
    }

    suspend fun getAlbumSongs(albumId: String): Result<List<ProviderSong>> = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/api/album/$albumId".toHttpUrlOrNull() ?: return@withContext Result.failure(IOException("Invalid URL"))
        val json = executeGet(url) ?: return@withContext Result.success(emptyList())
        val albumObj = json.optJSONObject("album") ?: return@withContext Result.success(emptyList())
        val tracksArray = albumObj.optJSONArray("tracks") ?: JSONArray()
        Result.success(parseSongsArray(tracksArray, defaultAlbumObj = albumObj))
    }

    suspend fun getAlbumDetails(albumId: String): Result<ProviderAlbum> = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/api/album/$albumId".toHttpUrlOrNull() ?: return@withContext Result.failure(IOException("Invalid URL"))
        val json = executeGet(url) ?: return@withContext Result.failure(IOException("Empty response"))
        val albumObj = json.optJSONObject("album") ?: return@withContext Result.failure(IOException("Missing album object"))
        val artistObj = albumObj.optJSONObject("artist")
        val artist = artistObj?.optString("name") ?: albumObj.optString("artist", "Various Artists")
        val artwork = albumObj.optString("cover_big")
            .ifBlank { albumObj.optString("cover_medium") }
            .ifBlank { albumObj.optString("cover_xl") }
            .ifBlank { null }
        Result.success(
            ProviderAlbum(
                providerId = albumId,
                title = cleanHtml(albumObj.optString("title")),
                artist = cleanHtml(artist),
                artworkUrl = artwork,
                songCount = albumObj.optInt("nbTracks", 0)
            )
        )
    }

    suspend fun getPlaylistSongs(playlistId: String): Result<List<ProviderSong>> = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/api/playlist/$playlistId".toHttpUrlOrNull() ?: return@withContext Result.failure(IOException("Invalid URL"))
        val json = executeGet(url) ?: return@withContext Result.success(emptyList())
        val playlistObj = json.optJSONObject("playlist") ?: return@withContext Result.success(emptyList())
        val tracksArray = playlistObj.optJSONArray("tracks") ?: JSONArray()
        Result.success(parseSongsArray(tracksArray))
    }

    suspend fun getArtistTopSongs(artistId: String): Result<List<ProviderSong>> = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/api/artist/$artistId/top-songs".toHttpUrlOrNull() ?: return@withContext Result.failure(IOException("Invalid URL"))
        val json = executeGet(url) ?: return@withContext Result.success(emptyList())
        val tracksArray = json.optJSONArray("tracks") ?: JSONArray()
        Result.success(parseSongsArray(tracksArray))
    }

    private var cachedChartsJson: JSONObject? = null
    private var chartsCacheTime = 0L

    private suspend fun getChartsJson(): JSONObject? {
        val now = System.currentTimeMillis()
        if (cachedChartsJson != null && now - chartsCacheTime < 300_000L) {
            return cachedChartsJson
        }
        val url = "$BASE_URL/api/charts".toHttpUrlOrNull() ?: return null
        val json = executeGet(url)
        if (json != null) {
            cachedChartsJson = json
            chartsCacheTime = now
        }
        return json
    }

    private suspend fun fetchTrackPreviewUrl(trackId: String): String? {
        val url = "$BASE_URL/api/search/tracks?query=$trackId&limit=1".toHttpUrlOrNull() ?: return null
        val json = executeGet(url) ?: return null
        val results = json.optJSONArray("results") ?: return null
        if (results.length() > 0) {
            val track = results.optJSONObject(0)
            return track?.optString("previewUrl")?.ifBlank { null }
        }
        return null
    }

    private fun parseSongsArray(array: JSONArray, defaultAlbumObj: JSONObject? = null): List<ProviderSong> {
        val songs = mutableListOf<ProviderSong>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (id.isBlank()) continue

            val title = cleanHtml(item.optString("title"))
            val artistObj = item.optJSONObject("artist")
            val artist = cleanHtml(artistObj?.optString("name") ?: item.optString("artist", "Unknown Artist"))

            val albumObj = item.optJSONObject("album") ?: defaultAlbumObj
            val album = cleanHtml(albumObj?.optString("title") ?: item.optString("album", "Unknown Album"))
            val albumId = albumObj?.optString("id")

            val durationSec = item.optLong("duration", 0L)
            val artworkUrl = albumObj?.optString("cover_big")
                ?.ifBlank { albumObj.optString("cover_medium") }
                ?.ifBlank { albumObj.optString("cover_xl") }
                ?.ifBlank { item.optString("cover_big") }
                ?.ifBlank { null }

            val previewUrl = item.optString("previewUrl")
            if (previewUrl.isNotBlank()) {
                previewUrlCache[id] = previewUrl
            }

            if (id.isNotBlank() && title.isNotBlank()) {
                trackMetaCache[id] = TrackMeta(title, artist, durationSec)
            }

            songs.add(
                ProviderSong(
                    providerId = id,
                    title = title,
                    artist = artist,
                    album = album,
                    albumId = albumId,
                    durationMs = durationSec * 1000L,
                    artworkUrl = artworkUrl,
                    codec = "mp3",
                    bitrate = 320
                )
            )
        }
        return songs
    }

    private fun parseAlbumsArray(array: JSONArray): List<ProviderAlbum> {
        val albums = mutableListOf<ProviderAlbum>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id")
            val title = cleanHtml(item.optString("title"))
            val artistObj = item.optJSONObject("artist")
            val artist = cleanHtml(artistObj?.optString("name") ?: item.optString("artist", "Various Artists"))
            val artwork = item.optString("cover_big")
                .ifBlank { item.optString("cover_medium") }
                .ifBlank { item.optString("cover_xl") }
                .ifBlank { null }
            val nbTracks = item.optInt("nbTracks", 0)

            if (id.isNotBlank() && title.isNotBlank()) {
                albums.add(
                    ProviderAlbum(
                        providerId = id,
                        title = title,
                        artist = artist,
                        artworkUrl = artwork,
                        songCount = nbTracks
                    )
                )
            }
        }
        return albums
    }

    private fun parseArtistsArray(array: JSONArray): List<ProviderArtist> {
        val artists = mutableListOf<ProviderArtist>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id")
            val name = cleanHtml(item.optString("name"))
            val artwork = item.optString("picture_big")
                .ifBlank { item.optString("picture_medium") }
                .ifBlank { item.optString("picture_xl") }
                .ifBlank { null }

            if (id.isNotBlank() && name.isNotBlank()) {
                artists.add(
                    ProviderArtist(
                        providerId = id,
                        name = name,
                        artworkUrl = artwork
                    )
                )
            }
        }
        return artists
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
            .header("User-Agent", USER_AGENT)
            .header("Origin", ORIGIN)
            .header("Referer", "$ORIGIN/")
            .header("Accept", "application/json, text/plain, */*")
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
