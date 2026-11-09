package com.naua_morphix_launcher.app.ui

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import androidx.recyclerview.widget.RecyclerView
import com.naua_morphix_launcher.app.databinding.ItemDockIconBinding
import com.naua_morphix_launcher.app.model.AppItem
import com.naua_morphix_launcher.app.model.IconShape

/** Состояние жеста на одной иконке дока. Вынесено из inner class: Kotlin не допускает enum внутри неё. */
private enum class GestureState { IDLE, PRESSED, LONG_PRESS_ARMED, DRAGGING, CANCELLED }

/**
 * Адаптер дока — плавающей капсулы с быстрым доступом в стиле MIUI.
 *
 * Док хранит только обычные приложения: папки и виджеты в нём не поддерживаются,
 * как и в MIUI. Слоты пустыми остаются быть не могут, но между иконками есть
 * промежутки за счёт padding у элементов списка.
 *
 * Долгое нажатие отдаёт жест наружу через [onDockItemStartDrag], чтобы
 * MainActivity продолжил перетаскивание в рамках уже существующей drag-сессии
 * рабочего стола.
 */
class DockAdapter(
    private val onAppClick: (AppItem) -> Unit,
    private val onAppLongClick: ((AppItem, View) -> Unit)? = null,
    private val onDockItemStartDrag: ((AppItem, View, Float, Float) -> Unit)? = null
) : RecyclerView.Adapter<DockAdapter.DockViewHolder>() {

    private val items = ArrayList<AppItem>()
    private var badgeCounts: Map<String, Int> = emptyMap()
    private var iconShape: IconShape = IconShape.SQUIRCLE
    private var showLabels: Boolean = true

    /**
     * FULL_WIDTH-режим: ячейка обязана растянуться на весь спан GridLayoutManager.
     *
     * Раньше ширина ячейки была захардкожена в 50dp: GridLayoutManager
     * измеряет ребёнка с lp.width > 0 как EXACTLY(50dp), и иконка
     * прилипала к ЛЕВОМУ краю своей трети-спана — при 3 иконках они
     * выстраивались «слева, левее середины, левее конца», вместо
     * равномерной раскладки по центру слотов.
     */
    private var fullWidthCells: Boolean = false

    fun submit(newItems: List<AppItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun getItemAt(position: Int): AppItem? = items.getOrNull(position)

    fun findPositionByPackage(packageName: String): Int =
        items.indexOfFirst { it.packageName == packageName }

    fun currentItems(): List<AppItem> = ArrayList(items)

    fun updateBadgeCounts(counts: Map<String, Int>) {
        if (badgeCounts == counts) return
        val old = badgeCounts
        badgeCounts = counts
        for (i in items.indices) {
            val pkg = items[i].packageName
            if ((old[pkg] ?: 0) != (counts[pkg] ?: 0)) notifyItemChanged(i)
        }
    }

    fun updateConfig(shape: IconShape, labels: Boolean, fullWidth: Boolean = fullWidthCells) {
        if (iconShape == shape && showLabels == labels && fullWidthCells == fullWidth) return
        iconShape = shape
        showLabels = labels
        fullWidthCells = fullWidth
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DockViewHolder {
        val binding = ItemDockIconBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return DockViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DockViewHolder, position: Int) {
        holder.bind(items[position])
    }

    /** Отложенный drag переживает переработку холдера только если явно отменён. */
    override fun onViewRecycled(holder: DockViewHolder) {
        super.onViewRecycled(holder)
        holder.cancelPendingDrag()
    }

    @SuppressLint("ClickableViewAccessibility")
    inner class DockViewHolder(private val binding: ItemDockIconBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private var gestureState = GestureState.IDLE
        private var downRawX = 0f
        private var downRawY = 0f
        private var downItem: AppItem? = null
        private var pendingLongPress: Runnable? = null

        fun cancelPendingDrag() {
            pendingLongPress?.let { TOUCH_HANDLER.removeCallbacks(it) }
            pendingLongPress = null
            gestureState = GestureState.IDLE
            downItem = null
        }

        fun bind(item: AppItem) {
            // Перепривязка того же приложения (бейдж, обновление списка) не должна
            // рвать жест в процессе: иначе долгое нажатие и перетаскивание пропадают.
            if (downItem?.packageName != item.packageName) cancelPendingDrag()
            // Ширина ячейки: 50dp в плавающей капсуле, Match_Parent по всему
            // спану в режиме FULL_WIDTH (GridLayoutManager сам делит ширину
            // поровну, иконка центрируется через gravity разметки).
            val lp = binding.root.layoutParams
            val targetWidth = if (fullWidthCells) {
                ViewGroup.LayoutParams.MATCH_PARENT
            } else {
                (50 * binding.root.resources.displayMetrics.density).toInt()
            }
            if (lp.width != targetWidth) {
                lp.width = targetWidth
                binding.root.layoutParams = lp
            }
            binding.dockDropTarget.visibility = View.GONE
            binding.dockIcon.setImageDrawable(item.icon)
            binding.dockIcon.shapeAppearanceModel = AppsAdapter.getShapeModel(iconShape)
            binding.dockLabel.text = item.label
            binding.dockLabel.visibility = if (showLabels) View.VISIBLE else View.GONE

            val unread = badgeCounts[item.packageName] ?: 0
            if (unread > 0) {
                binding.dockBadge.visibility = View.VISIBLE
                binding.dockBadge.text = if (unread > 99) "99+" else unread.toString()
            } else {
                binding.dockBadge.visibility = View.GONE
            }

            binding.root.setOnClickListener { onAppClick(item) }
            binding.root.setOnLongClickListener(null)

            binding.root.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        cancelPendingDrag()
                        gestureState = GestureState.PRESSED
                        downItem = item
                        downRawX = event.rawX
                        downRawY = event.rawY
                        pendingLongPress = Runnable {
                            if (gestureState == GestureState.PRESSED) {
                                gestureState = GestureState.LONG_PRESS_ARMED
                            }
                        }
                        TOUCH_HANDLER.postDelayed(
                            pendingLongPress!!,
                            ViewConfiguration.getLongPressTimeout().toLong()
                        )
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (gestureState == GestureState.PRESSED || gestureState == GestureState.LONG_PRESS_ARMED) {
                            val touchSlop = ViewConfiguration.get(v.context).scaledTouchSlop
                            val moved = kotlin.math.abs(event.rawX - downRawX) > touchSlop ||
                                kotlin.math.abs(event.rawY - downRawY) > touchSlop
                            if (moved) {
                                pendingLongPress?.let { TOUCH_HANDLER.removeCallbacks(it) }
                                pendingLongPress = null
                                if (gestureState == GestureState.LONG_PRESS_ARMED) {
                                    val pressedItem = downItem
                                    gestureState = GestureState.DRAGGING
                                    if (pressedItem != null && onDockItemStartDrag != null) {
                                        onDockItemStartDrag.invoke(pressedItem, binding.root, event.rawX, event.rawY)
                                    } else {
                                        gestureState = GestureState.CANCELLED
                                    }
                                } else {
                                    gestureState = GestureState.CANCELLED
                                }
                            }
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        val pressedItem = downItem
                        when (gestureState) {
                            GestureState.PRESSED -> v.performClick()
                            GestureState.LONG_PRESS_ARMED -> {
                                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                                if (pressedItem != null) onAppLongClick?.invoke(pressedItem, binding.root)
                            }
                            else -> Unit
                        }
                        cancelPendingDrag()
                    }
                    MotionEvent.ACTION_CANCEL -> cancelPendingDrag()
                }
                true
            }
        }
    }

    companion object {
        private val TOUCH_HANDLER = Handler(Looper.getMainLooper())
    }
}