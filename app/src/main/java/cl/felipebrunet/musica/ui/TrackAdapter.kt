package cl.felipebrunet.musica.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import cl.felipebrunet.musica.data.LibraryRepository
import cl.felipebrunet.musica.data.Track
import cl.felipebrunet.musica.databinding.ItemTrackBinding

class TrackAdapter(
    private val onClick: (Track, Int) -> Unit
) : RecyclerView.Adapter<TrackAdapter.Holder>() {

    private var items: List<Track> = emptyList()
    private var currentUri: String? = null
    private var sequentialNumbers: Boolean = false

    fun submit(tracks: List<Track>, playingUri: String?, sequentialNumbers: Boolean = false) {
        items = tracks
        currentUri = playingUri
        this.sequentialNumbers = sequentialNumbers
        notifyDataSetChanged()
    }

    fun setCurrent(uri: String?) {
        currentUri = uri
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount(): Int = items.size

    inner class Holder(private val binding: ItemTrackBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(track: Track, position: Int) {
            val number = if (!sequentialNumbers && track.trackNumber > 0) {
                track.trackNumber.toString()
            } else {
                (position + 1).toString()
            }
            binding.number.text = number
            binding.title.text = track.title
            binding.subtitle.text = track.artist
            val duration = LibraryRepository.formatDuration(track.durationMs)
            if (duration.isEmpty()) {
                binding.duration.visibility = View.GONE
            } else {
                binding.duration.visibility = View.VISIBLE
                binding.duration.text = duration
            }
            binding.root.isSelected = track.uri == currentUri
            binding.root.setOnClickListener { onClick(track, position) }
        }
    }
}
