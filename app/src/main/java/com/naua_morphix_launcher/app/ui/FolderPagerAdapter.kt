package com.naua_morphix_launcher.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.naua_morphix_launcher.app.databinding.ItemFolderPageBinding
import com.naua_morphix_launcher.app.model.AppItem
import com.naua_morphix_launcher.app.model.IconShape

class FolderPagerAdapter(
    private var pages: List<List<AppItem>>,
    private var iconShape: IconShape,
    private var showLabels: Boolean,
    private var iconScale: Float,
    private var smoothAnimations: Boolean = true,
    private val onAppClick: (AppItem) -> Unit,
    private val onAppLongClick: (AppItem, View) -> Unit,
    private val onAppStartDrag: ((AppItem, View, Float, Float) -> Unit)? = null,
    private val onAppClickWithView: ((AppItem, View) -> Unit)? = null,
    private val onItemsReordered: ((List<AppItem>) -> Unit)? = null
) : RecyclerView.Adapter<FolderPagerAdapter.FolderPageViewHolder>() {

    private val activeHolders = mutableSetOf<FolderPageViewHolder>()

    private var draggedItemPackage: String? = null

    fun getRecyclerViewForPage(pageIndex: Int): RecyclerView? {
        val holder = activeHolders.find { it.bindingAdapterPosition == pageIndex }
        return holder?.getRecyclerView()
    }

    fun getAdapterForPage(pageIndex: Int): AppsAdapter? {
        val holder = activeHolders.find { it.bindingAdapterPosition == pageIndex }
        return holder?.getAdapter()
    }

    fun getAllItems(): List<AppItem> {
        val result = mutableListOf<AppItem>()
        val total = itemCount
        for (i in 0 until total) {
            val holder = activeHolders.find { it.bindingAdapterPosition == i }
            if (holder != null) {
                result.addAll(holder.getCurrentItems())
            } else if (i in pages.indices) {
                result.addAll(pages[i])
            }
        }
        return result
    }

    fun setDraggedItemPackage(pkg: String?) {
        draggedItemPackage = pkg
        for (holder in activeHolders) {
            holder.setDraggedItemPackage(pkg)
        }
    }

    fun updateData(newPages: List<List<AppItem>>, shape: IconShape, labels: Boolean, scale: Float, smoothAnimations: Boolean = true) {
        this.pages = newPages
        this.iconShape = shape
        this.showLabels = labels
        this.iconScale = scale
        this.smoothAnimations = smoothAnimations
        notifyDataSetChanged()
    }

    override fun onViewAttachedToWindow(holder: FolderPageViewHolder) {
        super.onViewAttachedToWindow(holder)
        activeHolders.add(holder)
    }

    override fun onViewDetachedFromWindow(holder: FolderPageViewHolder) {
        super.onViewDetachedFromWindow(holder)
        activeHolders.remove(holder)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderPageViewHolder {
        val binding = ItemFolderPageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return FolderPageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FolderPageViewHolder, position: Int) {
        holder.bind(pages[position])
    }

    override fun getItemCount(): Int = pages.size

    inner class FolderPageViewHolder(private val binding: ItemFolderPageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private val adapter = AppsAdapter(
            onAppClick = onAppClick,
            onAppLongClick = { item, view, _ ->
                onAppLongClick(item, view)
            },
            onAppStartDrag = onAppStartDrag,
            onAppClickWithView = onAppClickWithView
        )

        init {
            binding.rvFolderPageGrid.layoutManager = GridLayoutManager(binding.root.context, 3)
            binding.rvFolderPageGrid.adapter = adapter
            binding.rvFolderPageGrid.setHasFixedSize(true)
            binding.rvFolderPageGrid.itemAnimator = null
        }

        fun getRecyclerView(): RecyclerView {
            return binding.rvFolderPageGrid
        }

        fun getAdapter(): AppsAdapter {
            return adapter
        }

        fun getCurrentItems(): List<AppItem> {
            return adapter.getItems()
        }

        fun setDraggedItemPackage(pkg: String?) {
            adapter.draggedItemPackage = pkg
        }

        fun bind(items: List<AppItem>) {
            adapter.draggedItemPackage = draggedItemPackage
            adapter.updateConfig(
                shape = iconShape,
                labels = showLabels,
                scale = iconScale,
                smoothAnimations = smoothAnimations
            )
            adapter.itemHeight = (88 * binding.root.resources.displayMetrics.density).toInt()
            adapter.submitList(items)
        }
    }
}
