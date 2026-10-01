package com.naua_morphix_launcher.app.ui

import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.naua_morphix_launcher.app.databinding.ItemAppWidgetGroupBinding
import com.naua_morphix_launcher.app.databinding.ItemWidgetPickerSubitemBinding
import com.naua_morphix_launcher.app.model.AppWidgetGroup
import com.naua_morphix_launcher.app.model.WidgetItem

class WidgetsAdapter(
    var smoothAnimations: Boolean = true,
    private val onWidgetSelected: (WidgetItem) -> Unit
) : RecyclerView.Adapter<WidgetsAdapter.GroupViewHolder>() {

    private val items = ArrayList<AppWidgetGroup>()

    fun submitList(newItems: List<AppWidgetGroup>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder {
        val binding = ItemAppWidgetGroupBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return GroupViewHolder(binding)
    }

    override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class GroupViewHolder(private val binding: ItemAppWidgetGroupBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(group: AppWidgetGroup) {
            binding.tvGroupAppName.text = group.appLabel
            binding.tvGroupWidgetCount.text = formatWidgetCount(group.widgets.size)

            if (group.appIcon != null) {
                binding.ivGroupAppIcon.setImageDrawable(group.appIcon)
            } else {
                binding.ivGroupAppIcon.setImageResource(android.R.drawable.sym_def_app_icon)
            }

            // Set chevron rotation and widgets container according to state
            binding.ivGroupChevron.rotation = if (group.isExpanded) 180f else 0f
            if (group.isExpanded) {
                populateWidgets(binding.llWidgetsContainer, group.widgets)
                binding.llWidgetsContainer.visibility = View.VISIBLE
            } else {
                binding.llWidgetsContainer.visibility = View.GONE
                binding.llWidgetsContainer.removeAllViews()
            }

            binding.llAppHeader.setOnClickListener {
                group.isExpanded = !group.isExpanded

                if (smoothAnimations) {
                    val parentGroup = binding.root as? ViewGroup
                    if (parentGroup != null) {
                        val transition = AutoTransition().apply {
                            duration = 180
                        }
                        TransitionManager.beginDelayedTransition(parentGroup, transition)
                    }

                    binding.ivGroupChevron.animate()
                        .rotation(if (group.isExpanded) 180f else 0f)
                        .setDuration(180)
                        .start()
                } else {
                    binding.ivGroupChevron.rotation = if (group.isExpanded) 180f else 0f
                }

                if (group.isExpanded) {
                    populateWidgets(binding.llWidgetsContainer, group.widgets)
                    binding.llWidgetsContainer.visibility = View.VISIBLE
                } else {
                    binding.llWidgetsContainer.visibility = View.GONE
                    binding.llWidgetsContainer.removeAllViews()
                }
            }
        }

        private fun populateWidgets(container: LinearLayout, widgets: List<WidgetItem>) {
            container.removeAllViews()
            val inflater = LayoutInflater.from(container.context)
            for (widget in widgets) {
                val subBinding = ItemWidgetPickerSubitemBinding.inflate(inflater, container, false)
                subBinding.tvSubWidgetTitle.text = widget.widgetLabel
                subBinding.tvSubWidgetDesc.text = widget.appLabel
                subBinding.tvSubWidgetSize.text = widget.sizeText

                val previewOrIcon = widget.preview ?: widget.icon
                if (previewOrIcon != null) {
                    subBinding.ivWidgetPreview.setImageDrawable(previewOrIcon)
                } else {
                    subBinding.ivWidgetPreview.setImageResource(android.R.drawable.sym_def_app_icon)
                }

                subBinding.root.setOnClickListener {
                    onWidgetSelected(widget)
                }

                container.addView(subBinding.root)
            }
        }

        private fun formatWidgetCount(count: Int): String {
            val mod10 = count % 10
            val mod100 = count % 100
            val word = when {
                mod100 in 11..19 -> "виджетов"
                mod10 == 1 -> "виджет"
                mod10 in 2..4 -> "виджета"
                else -> "виджетов"
            }
            return "$count $word"
        }
    }
}

