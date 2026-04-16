package org.jellyfin.mobile.player.ui

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import org.jellyfin.mobile.R
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.model.api.ImageType
import kotlin.time.Duration

class QueueSheetAdapter(
    private val apiClient: ApiClient,
    private val onItemClick: (Int) -> Unit,
) : ListAdapter<QueueItem, QueueSheetAdapter.QueueViewHolder>(QueueDiffCallback()) {

    var currentIndex: Int = 0
        set(value) {
            val old = field
            field = value
            if (old != value) {
                notifyItemChanged(old)
                notifyItemChanged(value)
            }
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.queue_item, parent, false)
        return QueueViewHolder(view)
    }

    override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    inner class QueueViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val accentBorder: View = itemView.findViewById(R.id.queue_item_accent_border)
        private val thumbnail: ImageView = itemView.findViewById(R.id.queue_item_thumbnail)
        private val durationText: TextView = itemView.findViewById(R.id.queue_item_duration)
        private val nowPlaying: TextView = itemView.findViewById(R.id.queue_item_now_playing)
        private val titleText: TextView = itemView.findViewById(R.id.queue_item_title)
        private val seriesText: TextView = itemView.findViewById(R.id.queue_item_series)

        fun bind(item: QueueItem, position: Int) {
            val isCurrent = position == currentIndex
            val isPrevious = position < currentIndex

            itemView.alpha = if (isPrevious) 0.5f else 1.0f
            accentBorder.isVisible = isCurrent
            nowPlaying.isVisible = isCurrent
            titleText.setTypeface(null, if (isCurrent) Typeface.BOLD else Typeface.NORMAL)
            titleText.text = item.title

            if (!item.seriesName.isNullOrEmpty()) {
                seriesText.text = item.seriesName
                seriesText.isVisible = true
            } else {
                seriesText.isVisible = false
            }

            durationText.text = formatDuration(item.duration)

            val imageUrl = apiClient.imageApi.getItemImageUrl(
                itemId = item.itemId,
                imageType = ImageType.PRIMARY,
                maxWidth = 240,
                tag = item.imageTag,
            )
            thumbnail.load(imageUrl)

            itemView.setOnClickListener { onItemClick(position) }
        }

        private fun formatDuration(duration: Duration): String {
            val totalSeconds = duration.inWholeSeconds
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return if (hours > 0) {
                "%d:%02d:%02d".format(hours, minutes, seconds)
            } else {
                "%d:%02d".format(minutes, seconds)
            }
        }
    }

    private class QueueDiffCallback : DiffUtil.ItemCallback<QueueItem>() {
        override fun areItemsTheSame(oldItem: QueueItem, newItem: QueueItem) =
            oldItem.itemId == newItem.itemId

        override fun areContentsTheSame(oldItem: QueueItem, newItem: QueueItem) =
            oldItem == newItem
    }
}
