package org.jellyfin.mobile.player.ui

import java.util.UUID
import kotlin.time.Duration

data class QueueItem(
    val itemId: UUID,
    val title: String,
    val seriesName: String?,
    val duration: Duration,
    val imageTag: String?,
)
