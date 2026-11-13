/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.features.streaming.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import chromahub.rhythm.app.features.streaming.domain.model.StreamingAlbum
import chromahub.rhythm.app.features.streaming.domain.model.StreamingPlaylist
import chromahub.rhythm.app.features.streaming.domain.model.StreamingSong
import chromahub.rhythm.app.features.streaming.presentation.viewmodel.StreamingMusicViewModel
import chromahub.rhythm.app.shared.presentation.components.icons.MaterialSymbolIcon
import chromahub.rhythm.app.shared.presentation.components.icons.Icon

@Composable
fun BrowseScreen(
    modifier: Modifier = Modifier,
    streamingViewModel: StreamingMusicViewModel? = null,
    onAlbumClick: (StreamingAlbum) -> Unit = {},
    onPlaylistClick: (StreamingPlaylist) -> Unit = {},
    onSongClick: (StreamingSong) -> Unit = {},
    onCategoryClick: (String) -> Unit = {}
) {
    val newReleases by streamingViewModel?.newReleases?.collectAsState() ?: remember { mutableStateOf(emptyList()) }
    val savedPlaylists by streamingViewModel?.savedPlaylists?.collectAsState() ?: remember { mutableStateOf(emptyList()) }

    LaunchedEffect(Unit) {
        if (newReleases.isEmpty()) {
            streamingViewModel?.loadHomeContent()
        }
    }

    val categories = remember {
        listOf(
            "Top Charts" to listOf(Color(0xFFE53935), Color(0xFFFF7043)),
            "New Releases" to listOf(Color(0xFF8E24AA), Color(0xFFBA68C8)),
            "Chill & Focus" to listOf(Color(0xFF1E88E5), Color(0xFF64B5F6)),
            "Hip-Hop / Rap" to listOf(Color(0xFFF4511E), Color(0xFFFFB74D)),
            "Pop" to listOf(Color(0xFFD81B60), Color(0xFFFF80AB)),
            "Electronic / Dance" to listOf(Color(0xFF00ACC1), Color(0xFF80DEEA)),
            "Rock & Alternative" to listOf(Color(0xFF3949AB), Color(0xFF7986CB)),
            "R&B / Soul" to listOf(Color(0xFF5E35B1), Color(0xFF9575CD))
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 120.dp, top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Text(
                    text = "Browse",
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 34.sp
                    ),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Explore trending releases, charts & editorial picks",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // New Releases Shelf
        if (newReleases.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "New Releases",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )

                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(newReleases.take(15)) { album ->
                            Column(
                                modifier = Modifier
                                    .width(140.dp)
                                    .clickable { onAlbumClick(album) },
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(140.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    if (album.artworkUri != null) {
                                        AsyncImage(
                                            model = album.artworkUri,
                                            contentDescription = album.title,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Icon(
                                            imageVector = MaterialSymbolIcon("album"),
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(48.dp)
                                                .align(Alignment.Center),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Text(
                                    text = album.title,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    text = album.artist,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // Editorial Playlists
        if (savedPlaylists.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Featured Playlists",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )

                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(savedPlaylists) { playlist ->
                            Column(
                                modifier = Modifier
                                    .width(150.dp)
                                    .clickable { onPlaylistClick(playlist) },
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(150.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    if (playlist.artworkUri != null) {
                                        AsyncImage(
                                            model = playlist.artworkUri,
                                            contentDescription = playlist.name,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Icon(
                                            imageVector = MaterialSymbolIcon("queue_music"),
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(48.dp)
                                                .align(Alignment.Center),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Text(
                                    text = playlist.name,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            }
                        }
                    }
                }
            }
        }

        // Browse Categories Grid
        item {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Browse by Category",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground
                )

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    categories.chunked(2).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            row.forEach { (name, colors) ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(90.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Brush.linearGradient(colors))
                                        .clickable { onCategoryClick(name) }
                                        .padding(14.dp)
                                ) {
                                    Text(
                                        text = name,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 16.sp
                                        ),
                                        color = Color.White,
                                        modifier = Modifier.align(Alignment.BottomStart)
                                    )
                                }
                            }
                            if (row.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}
