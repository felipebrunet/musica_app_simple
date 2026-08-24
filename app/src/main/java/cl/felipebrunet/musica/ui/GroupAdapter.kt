package cl.felipebrunet.musica.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import cl.felipebrunet.musica.data.Group
import cl.felipebrunet.musica.databinding.ItemGroupBinding

class GroupAdapter(
    private val onClick: (Group) -> Unit,
    private val onLongClick: ((Group) -> Boolean)? = null
) : RecyclerView.Adapter<GroupAdapter.Holder>() {

    private var items: List<Group> = emptyList()

    fun submit(groups: List<Group>) {
        items = groups
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemGroupBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class Holder(private val binding: ItemGroupBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(group: Group) {
            binding.title.text = group.title
            binding.subtitle.text = group.subtitle
            binding.root.setOnClickListener { onClick(group) }
            binding.root.setOnLongClickListener {
                onLongClick?.invoke(group) ?: false
            }
        }
    }
}
