package com.naua_morphix_launcher.app.ui

import android.graphics.Canvas
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.naua_morphix_launcher.app.databinding.ItemDesktopPageBinding
import com.naua_morphix_launcher.app.model.AppItem
import com.naua_morphix_launcher.app.model.IconShape

class DesktopPagerAdapter(
    private var pages: List<List<AppItem>>,
    private var spanCount: Int,
    private var iconShape: IconShape,
    private var showLabels: Boolean,
    private var iconScale: Float,
    private var showClockWidget: Boolean = true,
    private var smoothAnimations: Boolean = true,
    private val onAppClick: (AppItem) -> Unit,
    private val onAppLongClick: (AppItem, View, PageViewHolder?) -> Unit,
    private val onEmptyLongClick: () -> Unit,
    private val onTap: () -> Unit,
    private val onSwipeDown: () -> Unit = {},
    private val onSwipeUp: () -> Unit = {},
    private val onClockClick: () -> Unit = {},
    private val onDateClick: () -> Unit = {},
    private val onWeatherClick: () -> Unit = {},
    private val onItemsReordered: ((pageIndex: Int, newItems: List<AppItem>) -> Unit)? = null,
    private val onEdgeHover: ((direction: Int, pageIndex: Int, itemIndex: Int) -> Unit)? = null,
    private val onEdgeHoverCancel: (() -> Unit)? = null,
    private val onAppStartDrag: ((item: AppItem, view: View, rawX: Float, rawY: Float) -> Unit)? = null,
    private val onBindWidgetsForPage: ((pageIndex: Int, container: ViewGroup) -> Unit)? = null,
    private val onGetWidgetHostView: ((widgetId: Int, item: AppItem) -> View?)? = null,
    private val onAppClickWithView: ((AppItem, View) -> Unit)? = null,
    private val onSelectToggle: ((AppItem) -> Unit)? = null
) : RecyclerView.Adapter<DesktopPagerAdapter.PageViewHolder>() {

    private var badgeCounts: Map<String, Int> = emptyMap()
    private var isEditMode: Boolean = false
    private var selectedApps: Set<String> = emptySet()
    private val activeHolders = mutableSetOf<PageViewHolder>()

    fun getRecyclerViewForPage(pageIndex: Int): RecyclerView? {
        val holder = activeHolders.find { it.bindingAdapterPosition == pageIndex }
        return holder?.getRecyclerView()
    }

    fun updateData(newPages: List<List<AppItem>>, span: Int, shape: IconShape, labels: Boolean, scale: Float, showClock: Boolean, smoothAnimations: Boolean = true) {
        updateData(newPages, span, this.rowsCount, shape, labels, scale, showClock, smoothAnimations)
    }

    fun setDraggedItemPackage(pkg: String?) {
        for (holder in activeHolders) {
            holder.pageAdapter.draggedItemPackage = pkg
        }
    }

    fun setDraggedItemKey(key: String?) {
        for (holder in activeHolders) {
            holder.pageAdapter.draggedItemKey = key
        }
    }

    fun setEditMode(editMode: Boolean, selected: Set<String> = emptySet()) {
        this.isEditMode = editMode
        this.selectedApps = selected.toSet()
        for (holder in activeHolders) {
            holder.pageAdapter.setEditMode(editMode, selected.toSet())
        }
    }

    fun refreshWidgets() {
        for (holder in activeHolders) {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                val itemCount = holder.pageAdapter.itemCount
                if (itemCount > 0) {
                    holder.pageAdapter.notifyItemRangeChanged(0, itemCount)
                }
            }
        }
    }

    private var rowsCount: Int = 6

    fun updateData(
        newPages: List<List<AppItem>>,
        span: Int,
        rows: Int = 6,
        shape: IconShape,
        labels: Boolean,
        scale: Float,
        showClock: Boolean = true,
        smoothAnimations: Boolean = true
    ) {
        val oldSize = this.pages.size
        val configChanged = (this.spanCount != span || this.rowsCount != rows ||
            this.iconShape != shape || this.showLabels != labels ||
            this.iconScale != scale || this.showClockWidget != showClock ||
            this.smoothAnimations != smoothAnimations)

        this.pages = newPages
        this.spanCount = span
        this.rowsCount = rows
        this.iconShape = shape
        this.showLabels = labels
        this.iconScale = scale
        this.showClockWidget = showClock
        this.smoothAnimations = smoothAnimations

        for (holder in activeHolders) {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION && pos in newPages.indices) {
                holder.pageAdapter.updateConfig(shape, labels, scale, smoothAnimations)
                holder.bind(newPages[pos], pos)
            }
        }

        if (configChanged) {
            notifyDataSetChanged()
        } else {
            if (oldSize != newPages.size) {
                if (newPages.size > oldSize) {
                    notifyItemRangeInserted(oldSize, newPages.size - oldSize)
                } else {
                    notifyItemRangeRemoved(newPages.size, oldSize - newPages.size)
                }
            }
        }
    }

    fun updateBadgeCounts(counts: Map<String, Int>) {
        this.badgeCounts = counts
        for (holder in activeHolders) {
            holder.pageAdapter.updateBadgeCounts(counts)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val binding = ItemDesktopPageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PageViewHolder(binding)
    }

    override fun onViewAttachedToWindow(holder: PageViewHolder) {
        super.onViewAttachedToWindow(holder)
        activeHolders.add(holder)
        // setEditMode сам проверяет равенство и не шлёт notify при повторе
        holder.pageAdapter.setEditMode(isEditMode, selectedApps)
    }

    override fun onViewDetachedFromWindow(holder: PageViewHolder) {
        super.onViewDetachedFromWindow(holder)
        activeHolders.remove(holder)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        activeHolders.add(holder)
        holder.pageAdapter.isEditMode = isEditMode
        holder.bind(pages.getOrElse(position) { emptyList() }, position)
    }

    override fun onViewRecycled(holder: PageViewHolder) {
        super.onViewRecycled(holder)
        activeHolders.remove(holder)
    }

    override fun getItemCount(): Int = if (pages.isEmpty()) 1 else pages.size

    inner class PageViewHolder(private val binding: ItemDesktopPageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private var isItemMoved = false
        private var draggedHolder: RecyclerView.ViewHolder? = null

        val pageAdapter: AppsAdapter = AppsAdapter(
            onAppClick = onAppClick,
            onAppLongClick = { item, view, _ ->
                onAppLongClick(item, view, this@PageViewHolder)
            },
            onAppStartDrag = onAppStartDrag,
            onEmptyCellClick = { onTap() },
            onEmptyCellLongClick = { onEmptyLongClick() },
            onGetWidgetHostView = onGetWidgetHostView,
            onAppClickWithView = onAppClickWithView,
            onSelectToggle = onSelectToggle
        )

        fun getRecyclerView(): RecyclerView = binding.pageRecyclerView

        private val touchHelper: ItemTouchHelper

        init {
            val gridLayout = object : GridLayoutManager(binding.root.context, spanCount) {
                override fun canScrollVertically(): Boolean = false
                override fun canScrollHorizontally(): Boolean = false
            }
            gridLayout.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    // getItemOrNull вместо getItems(): этот метод зовётся N раз за
                    // каждый layout-проход, а getItems() копировал весь массив
                    val item = pageAdapter.getItemOrNull(position) ?: return 1
                    if (item.isWidget) {
                        return spanCount
                    }
                    if (item.isFolder) {
                        when (item.folderSize) {
                            "ENLARGED" -> return 2.coerceAtMost(spanCount)
                            "XXL" -> return spanCount
                            else -> return 1
                        }
                    }
                    return 1
                }
            }
            binding.pageRecyclerView.layoutManager = gridLayout
            binding.pageRecyclerView.adapter = pageAdapter
            binding.pageRecyclerView.setHasFixedSize(true)
            // offscreenPageLimit = 3 держит до 5 страниц по ~60 ячеек; кэш в 24
            // дополнительно держал разметку соседних экранов. На 4 ГБ это
            // лишняя память и лишние layout-проходы без видимой пользы.
            binding.pageRecyclerView.setItemViewCacheSize(6)
            binding.pageRecyclerView.itemAnimator = null // Устраняет микрофризы при свайпе
            binding.pageRecyclerView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                applyCellHeight()
            }

            // Поддержка Drag & Drop перемещения иконок
            touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.START or ItemTouchHelper.END,
                0
            ) {
                override fun isLongPressDragEnabled(): Boolean = false // Активируем через AppsAdapter.onAppLongClick

                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean {
                    isItemMoved = true
                    val from = viewHolder.bindingAdapterPosition
                    val to = target.bindingAdapterPosition
                    pageAdapter.moveItem(from, to)
                    return true
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

                override fun onChildDraw(
                    c: Canvas,
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    dX: Float,
                    dY: Float,
                    actionState: Int,
                    isCurrentlyActive: Boolean
                ) {
                    super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
                    if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && isCurrentlyActive) {
                        val loc = IntArray(2)
                        viewHolder.itemView.getLocationOnScreen(loc)
                        val centerX = loc[0] + viewHolder.itemView.width / 2f
                        val screenWidth = recyclerView.resources.displayMetrics.widthPixels
                        val edgeMargin = 55f * recyclerView.resources.displayMetrics.density
                        val pageIdx = bindingAdapterPosition
                        val itemIdx = viewHolder.bindingAdapterPosition

                        if (pageIdx != RecyclerView.NO_POSITION && itemIdx != RecyclerView.NO_POSITION) {
                            if (centerX > screenWidth - edgeMargin) {
                                onEdgeHover?.invoke(1, pageIdx, itemIdx)
                            } else if (centerX < edgeMargin) {
                                onEdgeHover?.invoke(-1, pageIdx, itemIdx)
                            } else {
                                onEdgeHoverCancel?.invoke()
                            }
                        }
                    }
                }

                override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                    super.onSelectedChanged(viewHolder, actionState)
                    if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                        draggedHolder = viewHolder
                        isItemMoved = false
                        viewHolder?.itemView?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        if (smoothAnimations) {
                            viewHolder?.itemView?.animate()?.scaleX(1.15f)?.scaleY(1.15f)?.alpha(0.85f)?.setDuration(100)?.start()
                        } else {
                            viewHolder?.itemView?.scaleX = 1.15f
                            viewHolder?.itemView?.scaleY = 1.15f
                            viewHolder?.itemView?.alpha = 0.85f
                        }
                    }
                }

                override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                    super.clearView(recyclerView, viewHolder)
                    onEdgeHoverCancel?.invoke()
                    if (smoothAnimations) {
                        viewHolder.itemView.animate()?.scaleX(1.0f)?.scaleY(1.0f)?.alpha(1.0f)?.setDuration(100)?.start()
                    } else {
                        viewHolder.itemView.scaleX = 1.0f
                        viewHolder.itemView.scaleY = 1.0f
                        viewHolder.itemView.alpha = 1.0f
                    }
                    val pageIdx = bindingAdapterPosition
                    if (pageIdx != RecyclerView.NO_POSITION) {
                        if (isItemMoved) {
                            onItemsReordered?.invoke(pageIdx, pageAdapter.getItems())
                        } else {
                            // Если пользователь зажал и отпустил палец без перемещения — открываем меню действий HyperOS
                            val pos = viewHolder.bindingAdapterPosition
                            if (pos != RecyclerView.NO_POSITION && pos in pageAdapter.getItems().indices) {
                                val item = pageAdapter.getItems()[pos]
                                onAppLongClick(item, viewHolder.itemView, this@PageViewHolder)
                            }
                        }
                    }
                    draggedHolder = null
                    isItemMoved = false
                }
            })
            touchHelper.attachToRecyclerView(binding.pageRecyclerView)

            // Перехват касаний по пространству рабочего стола
            binding.pageRecyclerView.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
                private var downX = 0f
                private var downY = 0f
                private var isLongPressTriggered = false
                private val longPressRunnable = Runnable {
                    isLongPressTriggered = true
                    onEmptyLongClick()
                }

                override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = e.x
                            downY = e.y
                            isLongPressTriggered = false
                            val child = rv.findChildViewUnder(e.x, e.y)
                            if (child == null) {
                                rv.postDelayed(longPressRunnable, 160)
                            }
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val diffX = Math.abs(e.x - downX)
                            val diffY = Math.abs(e.y - downY)
                            if (diffX > 15 || diffY > 15) {
                                rv.removeCallbacks(longPressRunnable)
                            }
                        }
                        MotionEvent.ACTION_UP -> {
                            rv.removeCallbacks(longPressRunnable)
                            val diffX = e.x - downX
                            val diffY = e.y - downY
                            val absX = Math.abs(diffX)
                            val absY = Math.abs(diffY)
                            if (absY > 70 && absY > absX * 1.3f && draggedHolder == null) {
                                if (diffY > 0) {
                                    onSwipeDown()
                                } else {
                                    onSwipeUp()
                                }
                            } else if (!isLongPressTriggered && absX < 15 && absY < 15) {
                                val child = rv.findChildViewUnder(e.x, e.y)
                                if (child == null) {
                                    onTap()
                                }
                            }
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            rv.removeCallbacks(longPressRunnable)
                        }
                    }
                    return false
                }
            })
        }

        fun startDrag(view: View) {
            val holder = binding.pageRecyclerView.findContainingViewHolder(view)
            if (holder != null) {
                touchHelper.startDrag(holder)
            }
        }

        private var boundPosition: Int = 0

        private fun applyCellHeight() {
            val rv = binding.pageRecyclerView
            val rvH = rv.height
            if (rvH > 0) {
                val rows = rowsCount.coerceAtLeast(1)
                val cellH = rvH / rows
                if (cellH > 0 && pageAdapter.itemHeight != cellH) {
                    pageAdapter.itemHeight = cellH
                }
            }
        }

        fun bind(items: List<AppItem>, position: Int) {
            boundPosition = position
            binding.pageWidgetsContainer.visibility = View.GONE
            (binding.pageRecyclerView.layoutManager as? GridLayoutManager)?.spanCount = spanCount
            // Set config and badges first (no-op if unchanged, no extra notify)
            pageAdapter.updateConfig(iconShape, showLabels, iconScale, smoothAnimations)
            pageAdapter.updateBadgeCounts(badgeCounts)
            applyCellHeight()
            // submitList is the single smart diff that triggers actual item updates
            pageAdapter.submitList(items)
        }
    }
}

