package com.naua_morphix_launcher.app.ui

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.naua_morphix_launcher.app.databinding.ItemDockIconBinding
import com.naua_morphix_launcher.app.model.AppItem
import com.naua_morphix_launcher.app.model.IconShape

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

    fun updateConfig(shape: IconShape, labels: Boolean) {
        if (iconShape == shape && showLabels == labels) return
        iconShape = shape
        showLabels = labels
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

        private var downRawX = 0f
        private var downRawY = 0f
        private var pendingDrag: Runnable? = null

        fun cancelPendingDrag() {
            pendingDrag?.let { TOUCH_HANDLER.removeCallbacks(it) }
            pendingDrag = null
        }

        fun bind(item: AppItem) {
            cancelPendingDrag()
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
            binding.root.setOnLongClickListener {
                onAppLongClick?.invoke(item, binding.root)
                true
            }

            binding.root.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        pendingDrag = Runnable {
                            onDockItemStartDrag?.invoke(item, binding.root, downRawX, downRawY)
                        }
                        TOUCH_HANDLER.postDelayed(pendingDrag!!, 200)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL,
                    MotionEvent.ACTION_MOVE -> cancelPendingDrag()
                }
                false
            }
        }
    }

    companion object {
        private val TOUCH_HANDLER = Handler(Looper.getMainLooper())
    }
}