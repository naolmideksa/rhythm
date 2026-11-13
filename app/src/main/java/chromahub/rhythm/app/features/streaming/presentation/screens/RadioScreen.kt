/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.features.streaming.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chromahub.rhythm.app.features.streaming.domain.model.StreamingSong
import chromahub.rhythm.app.features.streaming.presentation.viewmodel.StreamingMusicViewModel
import chromahub.rhythm.app.shared.presentation.components.icons.MaterialSymbolIcon
import chromahub.rhythm.app.shared.presentation.components.icons.Icon

@Composable
fun RadioScreen(
    modifier: Modifier = Modifier,
    streamingViewModel: StreamingMusicViewModel? = null,
    onSongClick: (StreamingSong) -> Unit = {},
    onStationClick: (String) -> Unit = {}
) {
    val recommendations by streamingViewModel?.recommendations?.collectAsState() ?: remember { mutableStateOf(emptyList()) }

    LaunchedEffect(Unit) {
        if (recommendations.isEmpty()) {
            streamingViewModel?.loadHomeContent()
        }
    }

    val stations = remember {
        listOf(
            Triple("Tunes 1 Live", "Today's hits & top trending tracks", listOf(Color(0xFFE91E63), Color(0xFF9C27B0))),
            Triple("Discovery Mix", "Fresh songs picked for you based on listening", listOf(Color(0xFF2196F3), Color(0xFF00BCD4))),
            Triple("Chill Station", "Lo-fi, ambient, and acoustic streams", listOf(Color(0xFF4CAF50), Color(0xFF8BC34A))),
            Triple("Energy & Gym", "High-BPM tracks to keep your tempo up", listOf(Color(0xFFFF5722), Color(0xFFFF9800))),
            Triple("Throwback Radio", "All-time classics and golden era tracks", listOf(Color(0xFF673AB7), Color(0xFF3F51B5)))
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 120.dp, top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Text(
                    text = "Radio",
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 34.sp
                    ),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Non-stop broadcast stations & infinite smart mixes",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Live Station Cards
        items(stations) { (name, description, colors) ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Brush.linearGradient(colors))
                    .clickable {
                        onStationClick(name)
                    }
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color.White)
                            )
                            Text(
                                text = "STATION",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                ),
                                color = Color.White.copy(alpha = 0.85f)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = name,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.9f)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.25f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = MaterialSymbolIcon("play_arrow", filled = true),
                            contentDescription = "Play Station",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
        }
    }
}
