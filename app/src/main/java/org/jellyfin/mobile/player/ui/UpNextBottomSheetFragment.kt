package org.jellyfin.mobile.player.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.core.content.ContextCompat
import coil3.load
import coil3.request.error
import coil3.request.placeholder
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.jellyfin.mobile.R
import org.jellyfin.mobile.databinding.FragmentUpNextBottomSheetBinding
import org.jellyfin.mobile.databinding.ItemUpNextBinding
import org.jellyfin.mobile.player.PlayerViewModel
import org.jellyfin.mobile.ui.content.ImageProvider
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType

class UpNextBottomSheetFragment : BottomSheetDialogFragment() {

    private val viewModel: PlayerViewModel by viewModels({ requireParentFragment() })
    private var _binding: FragmentUpNextBottomSheetBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentUpNextBottomSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = UpNextAdapter { index ->
            viewModel.playQueueItem(index)
            dismiss()
        }
        binding.upNextRecyclerView.adapter = adapter

        viewModel.queueItems.observe(viewLifecycleOwner) { items ->
            adapter.submitList(items)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(): UpNextBottomSheetFragment {
            return UpNextBottomSheetFragment()
        }
    }

    private class UpNextAdapter(
        private val onItemClicked: (Int) -> Unit
    ) : ListAdapter<BaseItemDto, UpNextViewHolder>(DiffCallback) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UpNextViewHolder {
            val binding = ItemUpNextBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return UpNextViewHolder(binding, onItemClicked)
        }

        override fun onBindViewHolder(holder: UpNextViewHolder, position: Int) {
            holder.bind(getItem(position), position)
        }

        companion object {
            private val DiffCallback = object : DiffUtil.ItemCallback<BaseItemDto>() {
                override fun areItemsTheSame(oldItem: BaseItemDto, newItem: BaseItemDto): Boolean {
                    return oldItem.id == newItem.id
                }

                override fun areContentsTheSame(oldItem: BaseItemDto, newItem: BaseItemDto): Boolean {
                    return oldItem == newItem
                }
            }
        }
    }

    private class UpNextViewHolder(
        private val binding: ItemUpNextBinding,
        private val onItemClicked: (Int) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: BaseItemDto, position: Int) {
            binding.root.setOnClickListener { onItemClicked(position) }
            binding.title.text = item.name
            binding.subtitle.text = getSubtitle(item, binding.root.context)

            val imageUri = ImageProvider.buildItemUri(
                itemId = item.id,
                imageType = ImageType.PRIMARY,
                imageTag = item.imageTags?.get(ImageType.PRIMARY)
            )
            val context = binding.root.context
            binding.thumbnail.load(imageUri) {
                placeholder(ContextCompat.getDrawable(context, R.drawable.ic_local_movies_white_64))
                error(ContextCompat.getDrawable(context, R.drawable.ic_local_movies_white_64))
            }
        }

        private fun getSubtitle(item: BaseItemDto, context: Context): String {
            return when (item.type) {
                BaseItemKind.EPISODE -> {
                    val season = item.parentIndexNumber
                    val episode = item.indexNumber
                    if (season != null && episode != null) {
                        context.getString(R.string.season_episode_format, season, episode)
                    } else {
                        item.seriesName ?: ""
                    }
                }
                else -> item.productionYear?.toString() ?: ""
            }
        }
    }
}
