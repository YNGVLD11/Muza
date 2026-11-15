package com.muza.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.muza.app.service.DownloadStatus
import com.muza.app.service.QueueItem
import com.muza.app.ui.theme.StatusError
import com.muza.app.ui.theme.StatusSuccess

@Composable
fun QueueItemCard(
    item: QueueItem,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
    onOpenFolder: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardShape = RoundedCornerShape(12.dp)
    val thumbShape = RoundedCornerShape(8.dp)
    val isFetching = item.status == DownloadStatus.FETCHING_INFO
    val shimmer = shimmerBrush()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, cardShape)
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail (16:9 ratio) with Shimmer animation during FETCHING_INFO
            Box(
                modifier = Modifier
                    .width(72.dp)
                    .height(44.dp)
                    .clip(thumbShape)
                    .then(
                        if (isFetching) Modifier.background(shimmer)
                        else Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (!isFetching && !item.thumbnailUrl.isNullOrEmpty()) {
                    SubcomposeAsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(item.thumbnailUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                        loading = {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        },
                        error = {
                            Icon(
                                imageVector = Icons.Outlined.MusicNote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    )
                } else if (!isFetching) {
                    Icon(
                        imageVector = Icons.Outlined.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Title and Status
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                if (isFetching) {
                    // Shimmering title bar while loading metadata
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(shimmer)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Получение информации...",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.secondary
                    )
                } else {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(3.dp))

                    when (item.status) {
                        DownloadStatus.DOWNLOADING, DownloadStatus.CONVERTING, DownloadStatus.SAVING -> {
                            LinearProgressIndicator(
                                progress = { (item.progress / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = item.statusText,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }

                        DownloadStatus.QUEUED -> {
                            Text(
                                text = "В очереди",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }

                        DownloadStatus.COMPLETED -> {
                            Text(
                                text = "Скачано: ${item.fileName ?: "MP3"}",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                color = StatusSuccess,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        DownloadStatus.FAILED -> {
                            Text(
                                text = item.errorMessage ?: "Ошибка загрузки",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                color = StatusError,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        DownloadStatus.CANCELLED -> {
                            Text(
                                text = "Отменено",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }

                        DownloadStatus.FETCHING_INFO -> {}
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Action button (always available, including during FETCHING_INFO)
            when (item.status) {
                DownloadStatus.DOWNLOADING, DownloadStatus.CONVERTING, DownloadStatus.SAVING -> {
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Отмена",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                DownloadStatus.FETCHING_INFO, DownloadStatus.QUEUED -> {
                    IconButton(
                        onClick = onRemove,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Удалить из очереди",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                DownloadStatus.COMPLETED -> {
                    IconButton(
                        onClick = onOpenFolder,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FolderOpen,
                            contentDescription = "Открыть папку",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                DownloadStatus.FAILED, DownloadStatus.CANCELLED -> {
                    IconButton(
                        onClick = onRetry,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = "Повторить",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}
