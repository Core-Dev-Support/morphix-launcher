package com.naua_morphix_launcher.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.shape.RelativeCornerSize
import com.google.android.material.shape.ShapeAppearanceModel
import com.naua_morphix_launcher.app.databinding.ItemAppGridBinding
import com.naua_morphix_launcher.app.databinding.ItemWidgetCellBinding
import com.naua_morphix_launcher.app.model.AppItem
import com.naua_morphix_launcher.app.model.IconShape
import com.naua_morphix_launcher.app.R
import android.os.Handler
import android.os.Looper
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppsAdapter(
    private val onAppClick: (AppItem) -> Unit,
    private val onAppLongClick: ((AppItem, View, RecyclerView.ViewHolder) -> Unit)? = null,
    private val onAppStartDrag: ((AppItem, View, Float, Float) -> Unit)? = null,
    private val onEmptyCellClick: (() -> Unit)? = null,
    private val onEmptyCellLongClick: (() -> Unit)? = null,
    private val onGetWidgetHostView: ((widgetId: Int, item: AppItem) -> View?)? = null,
    private val onAppClickWithView: ((AppItem, View) -> Unit)? = null,
    private val onSelectToggle: ((AppItem) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = ArrayList<AppItem>()
    private var iconShape: IconShape = IconShape.SQUIRCLE
    private var showLabels: Boolean = true
    private var iconScale: Float = 1.0f
    private var smoothAnimations: Boolean = true
    private var badgeCounts: Map<String, Int> = emptyMap()
        private set
        
    /**
     * Стабильный ключ элемента. packageName недостаточно: один и тот же пакет
     * существует в двух экземплярах (основное пространство и клон второго),
     * и по packageName DiffUtil считал их одним элементом, а поиск при drag
     * всегда попадал в первое совпадение — то есть в иконку не того профиля.
     */
    private fun identityOf(item: AppItem): String = when {
        item.isEmpty -> ""
        item.isFolder -> "f:${item.folderId}"
        item.isWidget -> "w:${item.widgetId}"
        else -> "a:${item.packageName}:${item.userHandle?.hashCode() ?: 0}"
    }

    var draggedItemKey: String? = null
        set(value) {
            if (field != value) {
                val oldKey = field
                field = value

                val oldIndex = if (oldKey != null) items.indexOfFirst { identityOf(it) == oldKey } else -1
                val newIndex = if (value != null) items.indexOfFirst { identityOf(it) == value } else -1

                if (oldIndex != -1) notifyItemChanged(oldIndex, PAYLOAD_DRAG_STATE)
                if (newIndex != -1) notifyItemChanged(newIndex, PAYLOAD_DRAG_STATE)
            }
        }

    /** Совместимость со старыми вызовами по packageName. */
    var draggedItemPackage: String?
        get() = draggedItemKey?.removePrefix("a:")?.substringBefore(':')
        set(value) {
            draggedItemKey = value?.let { "a:$it:0" }
        }

    /**
     * Флаг «долгое нажатие уже обработано, тап глотать».
     *
     * Держим его на адаптере, а не в холдере: долгое нажатие по пустому месту
     * открывает режим редактирования, который вызывает updateHomeScreenApps()
     * и полностью перебиндивает сетку. Локальная переменная холдера при этом
     * терялась, и ACTION_UP после отпускания пальца воспринимался как обычный
     * тап — а двойной тап в режиме редактирования выходил из него.
     */
    private var longPressConsumed = false

    /** Вызывается холдером перед ACTION_UP, чтобы не отправить «тап» после долгого нажатия. */
    fun consumeTapAfterLongPress(): Boolean {
        if (longPressConsumed) {
            longPressConsumed = false
            return true
        }
        return false
    }

    private fun markLongPressConsumed() {
        longPressConsumed = true
    }

    private var attachedRecyclerView: RecyclerView? = null
    var isEditMode: Boolean = false
        private set
    var selectedApps: Set<String> = emptySet()
        private set

    fun setEditMode(editMode: Boolean, selected: Set<String>) {
        val sel = selected.toSet()
        // Раньше сеттеры сами слали notify, а метод слал третий — три полных
        // notifyItemRangeChanged на одно переключение и на каждый свайп страницы
        if (this.isEditMode == editMode && this.selectedApps == sel) return
        this.isEditMode = editMode
        this.selectedApps = sel
        notifyItemRangeChanged(0, itemCount, PAYLOAD_EDIT_MODE)
    }

    var itemHeight: Int = ViewGroup.LayoutParams.WRAP_CONTENT
        set(value) {
            if (field != value) {
                field = value
                notifyItemRangeChanged(0, items.size)
            }
        }

    /**
     * Доступ без копирования. SpanSizeLookup вызывает это N раз за каждый
     * layout-проход, а getItems() копировал массив — получалось O(N²)
     * копий и N аллокаций ArrayList на проход.
     */
    fun getItemOrNull(position: Int): AppItem? = items.getOrNull(position)

    /** Снимок списка — только там, где список реально нужно скопировать. */
    fun getItems(): List<AppItem> = ArrayList(items)

    fun swapItems(from: Int, to: Int) {
        if (from in items.indices && to in items.indices) {
            val temp = items[from]
            items[from] = items[to]
            items[to] = temp
            notifyItemMoved(from, to)
        }
    }

    fun setItem(index: Int, item: AppItem) {
        if (index in items.indices) {
            items[index] = item
            notifyItemChanged(index)
        }
    }

    fun getItem(index: Int): AppItem? {
        if (index in items.indices) return items[index]
        return null
    }


    private val submitScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var submitGeneration = 0

    fun submitList(newItems: List<AppItem>) {
        // быстрый выход, когда данные не изменились: раньше даже он
        // копировал оба списка и считал DiffUtil
        if (items.size == newItems.size && items.indices.all { items[it] == newItems[it] }) return

        val oldItems = ArrayList(items)
        val copiedNewItems = ArrayList(newItems)
        val generation = ++submitGeneration

        submitScope.launch {
            // DiffUtil — O(N*D); на каждый символ поиска он считался
            // на главном потоке и блокировал кадр
            val diffResult = withContext(Dispatchers.Default) {
                androidx.recyclerview.widget.DiffUtil.calculateDiff(object : androidx.recyclerview.widget.DiffUtil.Callback() {
                    override fun getOldListSize(): Int = oldItems.size
                    override fun getNewListSize(): Int = copiedNewItems.size

                    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                        val old = oldItems[oldItemPosition]
                        val new = copiedNewItems[newItemPosition]
                        // пустые слоты взаимозаменяемы; раньше сравнение по позиции
                        // превращало сдвиг пустой ячейки в remove+insert, то есть
                        // в переинфлят item_app_grid.xml целиком
                        if (old.isEmpty || new.isEmpty) return old.isEmpty && new.isEmpty
                        return identityOf(old) == identityOf(new)
                    }

                    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                        val old = oldItems[oldItemPosition]
                        val new = copiedNewItems[newItemPosition]
                        if (old.isEmpty || new.isEmpty) return old.isEmpty && new.isEmpty
                        return old.label == new.label &&
                               old.isFolder == new.isFolder &&
                               old.folderSize == new.folderSize &&
                               old.isWidget == new.isWidget &&
                               old.widgetId == new.widgetId &&
                               old.widgetSpanX == new.widgetSpanX &&
                               old.widgetSpanY == new.widgetSpanY &&
                               old.isSecondSpace == new.isSecondSpace &&
                               old.userHandle == new.userHandle &&
                               old.icon === new.icon &&
                               // не только size: перестановка двух иконок в папке
                               // оставляла старые мини-превью
                               old.folderApps == new.folderApps
                    }
                })
            }

            // пока считался diff, пришёл более новый submitList — его результат важнее
            if (generation != submitGeneration) return@launch

            items.clear()
            items.addAll(copiedNewItems)
            diffResult.dispatchUpdatesTo(this@AppsAdapter)
        }
    }

    fun updateConfig(shape: IconShape, labels: Boolean, scale: Float, smoothAnimations: Boolean = true) {
        // раньше полный notifyItemRangeChanged слался всегда, даже когда
        // конфигурация не менялась, а вызывался он на каждый updateData
        if (this.iconShape == shape && this.showLabels == labels
            && this.iconScale == scale && this.smoothAnimations == smoothAnimations
        ) return
        this.iconShape = shape
        this.showLabels = labels
        this.iconScale = scale
        this.smoothAnimations = smoothAnimations
        notifyItemRangeChanged(0, itemCount, PAYLOAD_CONFIG)
    }

    fun updateBadgeCounts(counts: Map<String, Int>) {
        // === на ссылку: getAllBadgeCounts() возвращает новый объект каждый вызов,
        // поэтому проверка никогда не срабатывала
        if (this.badgeCounts == counts) return
        val oldCounts = this.badgeCounts
        this.badgeCounts = counts
        // payload вместо полного bind: иначе DefaultItemAnimator запускал
        // change-анимацию с кросс-фейдом на каждое уведомление
        for (i in items.indices) {
            val pkg = items[i].packageName
            if ((oldCounts[pkg] ?: 0) != (counts[pkg] ?: 0)) {
                notifyItemChanged(i, PAYLOAD_BADGE)
            }
        }
    }

    fun moveItem(fromPosition: Int, toPosition: Int) {
        if (fromPosition in items.indices && toPosition in items.indices && fromPosition != toPosition) {
            if (fromPosition < toPosition) {
                for (i in fromPosition until toPosition) {
                    Collections.swap(items, i, i + 1)
                }
            } else {
                for (i in fromPosition downTo toPosition + 1) {
                    Collections.swap(items, i, i - 1)
                }
            }
            notifyItemMoved(fromPosition, toPosition)
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.attachedRecyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        this.attachedRecyclerView = null
    }

    fun setItemHighlighted(position: Int, highlighted: Boolean, draggedItem: AppItem? = null) {
        if (position in items.indices && !items[position].isEmpty) {
            val holder = attachedRecyclerView?.findViewHolderForAdapterPosition(position) as? AppViewHolder
            holder?.setFolderDropHighlight(highlighted, draggedItem)
        }
    }

    fun setEmptyDropGhost(position: Int, draggedItem: AppItem?) {
        if (position in items.indices && items[position].isEmpty) {
            val holder = attachedRecyclerView?.findViewHolderForAdapterPosition(position) as? AppViewHolder
            holder?.setEmptyDropGhost(draggedItem)
        }
    }

    fun setSwapHighlight(position: Int, highlighted: Boolean) {
        if (position in items.indices && !items[position].isEmpty) {
            val holder = attachedRecyclerView?.findViewHolderForAdapterPosition(position) as? AppViewHolder
            holder?.setSwapHighlight(highlighted)
        }
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int {
        val item = items[position]
        return if (item.isWidget) VIEW_TYPE_WIDGET else VIEW_TYPE_APP
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_WIDGET) {
            val binding = ItemWidgetCellBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            WidgetViewHolder(binding)
        } else {
            val binding = ItemAppGridBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            AppViewHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        val item = items.getOrNull(position) ?: return
        var needsFullBind = payloads.isEmpty()

        // RecyclerView объединяет все payload'ы позиции в ОДИН список.
        // Обработка через if/else if теряла часть изменений при батче:
        // например, EDIT_MODE проходил, а DRAG_STATE молча выбрасывался,
        // и перетаскиваемая иконка оставалась видимой «призраком».
        if (payloads.contains(PAYLOAD_EDIT_MODE)) {
            if (holder is AppViewHolder && !item.isEmpty) {
                holder.updateEditMode(isEditMode)
            } else {
                needsFullBind = true
            }
        }
        if (payloads.contains(PAYLOAD_DRAG_STATE)) {
            when (holder) {
                is AppViewHolder -> holder.applyDragAlpha(item)
                is WidgetViewHolder -> holder.applyDragAlpha(item)
            }
        }
        if (payloads.contains(PAYLOAD_BADGE)) {
            if (holder is AppViewHolder) {
                holder.applyBadge(item)
            } else {
                needsFullBind = true
            }
        }
        if (payloads.contains(PAYLOAD_CONFIG)) {
            needsFullBind = true
        }

        if (needsFullBind) {
            when (holder) {
                is WidgetViewHolder -> holder.bind(item)
                is AppViewHolder -> holder.bind(item)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items.getOrNull(position) ?: return
        when (holder) {
            is WidgetViewHolder -> holder.bind(item)
            is AppViewHolder -> holder.bind(item)
        }
    }

    /**
     * Handler-ы отложенных long-press отменяются при переработке холдера.
     * Раньше отмен шёл только внутри замыкания того же bind, поэтому после
     * переработки старый Runnable срабатывал через 200 мс и начинал drag
     * с уже отпущенным пальцем.
     */
    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        when (holder) {
            is AppViewHolder -> holder.cancelPendingCallbacks()
            is WidgetViewHolder -> holder.detach()
        }
    }

    inner class AppViewHolder(private val binding: ItemAppGridBinding) :
        RecyclerView.ViewHolder(binding.root) {

        /** Отложенный long-press на ячейке. */
        private var pendingLongPress: Runnable? = null
        private var downX = 0f
        private var downY = 0f
        private var downRawX = 0f
        private var downRawY = 0f

        private val enlargedMiniViews: Array<android.widget.ImageView> = arrayOf(
            binding.ivEnlargedMini1,
            binding.ivEnlargedMini2,
            binding.ivEnlargedMini3,
            binding.ivEnlargedMini4
        )
        private val folderMiniViews: Array<android.widget.ImageView> = arrayOf(
            binding.folderMiniIcon1,
            binding.folderMiniIcon2,
            binding.folderMiniIcon3,
            binding.folderMiniIcon4
        )

        fun cancelPendingCallbacks() {
            pendingLongPress?.let { TOUCH_HANDLER.removeCallbacks(it) }
            pendingLongPress = null
        }

        fun applyDragAlpha(item: AppItem) {
            val draggedKey = draggedItemKey
            val isDragged = draggedKey != null && identityOf(item) == draggedKey
            val target = if (isDragged) 0f else 1f
            if (binding.root.alpha != target) binding.root.alpha = target
        }

        fun applyBadge(item: AppItem) {
            renderBadge(item)
        }

        /** Высоту задаём через сеттер layoutParams — прямая мутация поля не вызывает requestLayout(). */
        private fun applyCellHeight(height: Int) {
            if (height <= 0) return
            val lp = binding.root.layoutParams ?: return
            if (lp.height == height) return
            lp.height = height
            binding.root.layoutParams = lp
        }

        fun bind(item: AppItem) {
            val density = binding.root.resources.displayMetrics.density

            // Динамический адаптивный размер иконки под высоту ячейки сетки, чтобы подписи ВСЕГДА помещались
            val finalIconSize: Int
            if (itemHeight > 0) {
                applyCellHeight(itemHeight)
                val reservedForLabel = if (showLabels) (18 * density).toInt() else (4 * density).toInt()
                val availableHeight = (itemHeight - reservedForLabel).coerceAtLeast((28 * density).toInt())
                val baseSize = availableHeight.coerceAtMost((56 * density).toInt())
                finalIconSize = (baseSize * iconScale).toInt().coerceIn(
                    (28 * density).toInt(),
                    availableHeight.coerceAtLeast((32 * density).toInt())
                )
            } else {
                finalIconSize = (56 * density * iconScale).toInt().coerceIn((36 * density).toInt(), (72 * density).toInt())
            }

            val iconLp = binding.iconContainer.layoutParams
            if (iconLp.width != finalIconSize || iconLp.height != finalIconSize) {
                iconLp.width = finalIconSize
                iconLp.height = finalIconSize
                binding.iconContainer.layoutParams = iconLp
            }

            // Сброс свойств (важно для переиспользования View)
            cancelPendingCallbacks()
            binding.appIcon.alpha = 1.0f
            binding.appLabel.alpha = 1.0f
            binding.root.scaleX = 1.0f
            binding.root.scaleY = 1.0f
            applyDragAlpha(item)

            if (item.isEmpty) {
                // Полный сброс: ветка enlarged выставляет iconContainer = GONE и
                // layoutEnlargedFolder = VISIBLE, но не возвращает их назад.
                // Без этого переработанный холдер показывал чужое превью папки,
                // и клик по нему открывал прошлое приложение.
                binding.appLabel.visibility = View.GONE
                binding.appIcon.visibility = View.INVISIBLE
                binding.iconContainer.visibility = View.VISIBLE
                binding.layoutFolderPreview.visibility = View.GONE
                binding.layoutEnlargedFolder.visibility = View.GONE
                binding.ivSelectCircle.visibility = View.GONE
                binding.ivEnlargedSelectCircle.visibility = View.GONE
                binding.cloneBadge.visibility = View.GONE
                binding.notificationBadge.visibility = View.GONE
                binding.folderDropHighlight.visibility = View.GONE

                // Снимаем слушатели с внутренних вьюх enlarged-превью
                binding.enlargedApp1.setOnClickListener(null)
                binding.enlargedApp2.setOnClickListener(null)
                binding.enlargedApp3.setOnClickListener(null)
                binding.enlargedQuadrant.setOnClickListener(null)
                binding.ivEnlargedSelectCircle.setOnClickListener(null)
                binding.ivSelectCircle.setOnClickListener(null)

                // флаг не сбрасываем здесь: долгое нажатие вызывает
                // перебиндинг сетки, и сброс в bind() ломал бы защиту от тапа
                binding.root.setOnTouchListener { v, event ->
                    when (event.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            downX = event.rawX
                            downY = event.rawY
                            pendingLongPress = Runnable {
                                markLongPressConsumed()
                                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                                onEmptyCellLongClick?.invoke()
                            }
                            TOUCH_HANDLER.postDelayed(pendingLongPress!!, 160)
                        }
                        android.view.MotionEvent.ACTION_MOVE -> {
                            val diffX = Math.abs(event.rawX - downX)
                            val diffY = Math.abs(event.rawY - downY)
                            if (diffX > 12 * density || diffY > 12 * density) {
                                cancelPendingCallbacks()
                            }
                        }
                        android.view.MotionEvent.ACTION_UP -> {
                            cancelPendingCallbacks()
                            if (!consumeTapAfterLongPress()) {
                                val diffX = Math.abs(event.rawX - downX)
                                val diffY = Math.abs(event.rawY - downY)
                                if (diffX <= 12 * density && diffY <= 12 * density) {
                                    v.performClick()
                                    onEmptyCellClick?.invoke()
                                }
                            }
                        }
                        android.view.MotionEvent.ACTION_CANCEL -> cancelPendingCallbacks()
                    }
                    true
                }
                binding.root.setOnClickListener(null)
                binding.root.setOnLongClickListener(null)
                return
            }

            val isEnlarged = item.isFolder && (item.folderSize == "ENLARGED" || item.folderSize == "XXL")
            if (isEnlarged) {
                binding.iconContainer.visibility = View.GONE
                binding.appLabel.visibility = View.GONE
                binding.layoutEnlargedFolder.visibility = View.VISIBLE

                if (itemHeight > 0) {
                    applyCellHeight(itemHeight * 2)
                }

                val activity = binding.root.context as? com.naua_morphix_launcher.app.MainActivity
                if (activity != null) {
                    binding.layoutEnlargedFolder.setGlassEnabled(activity.currentSettings.isGlassEnabled)
                }

                // App 1
                val app1 = item.folderApps.getOrNull(0)
                if (app1 != null) {
                    binding.enlargedApp1.visibility = View.VISIBLE
                    binding.ivEnlargedIcon1.setImageDrawable(app1.icon)
                    binding.ivEnlargedIcon1.shapeAppearanceModel = getShapeModel(iconShape)
                    binding.tvEnlargedLabel1.text = app1.label
                    binding.enlargedApp1.setOnClickListener { onAppClick(app1) }
                } else {
                    binding.enlargedApp1.visibility = View.INVISIBLE
                }

                // App 2
                val app2 = item.folderApps.getOrNull(1)
                if (app2 != null) {
                    binding.enlargedApp2.visibility = View.VISIBLE
                    binding.ivEnlargedIcon2.setImageDrawable(app2.icon)
                    binding.ivEnlargedIcon2.shapeAppearanceModel = getShapeModel(iconShape)
                    binding.tvEnlargedLabel2.text = app2.label
                    binding.enlargedApp2.setOnClickListener { onAppClick(app2) }
                } else {
                    binding.enlargedApp2.visibility = View.INVISIBLE
                }

                // App 3
                val app3 = item.folderApps.getOrNull(2)
                if (app3 != null) {
                    binding.enlargedApp3.visibility = View.VISIBLE
                    binding.ivEnlargedIcon3.setImageDrawable(app3.icon)
                    binding.ivEnlargedIcon3.shapeAppearanceModel = getShapeModel(iconShape)
                    binding.tvEnlargedLabel3.text = app3.label
                    binding.enlargedApp3.setOnClickListener { onAppClick(app3) }
                } else {
                    binding.enlargedApp3.visibility = View.INVISIBLE
                }

                // 4th Quadrant (Folder Open Trigger)
                val remainingApps = if (item.folderApps.size > 3) item.folderApps.drop(3) else item.folderApps
                // без listOf: он создавал новый ArrayList на каждую ячейку-папку
                val miniViews = enlargedMiniViews
                for (i in 0..3) {
                    val mini = miniViews[i]
                    if (i < remainingApps.size) {
                        mini.visibility = View.VISIBLE
                        mini.setImageDrawable(remainingApps[i].icon)
                    } else {
                        mini.visibility = View.INVISIBLE
                    }
                }
                binding.tvEnlargedFolderTitle.text = item.label
                binding.enlargedQuadrant.setOnClickListener {
                    onAppClick(item)
                }

                // Edit Mode selection circle for enlarged folder
                binding.ivSelectCircle.visibility = View.GONE
                binding.ivEnlargedSelectCircle.visibility = if (isEditMode) View.VISIBLE else View.GONE
                if (isEditMode) {
                    val selKey = "folder:${item.folderId}"
                    if (selectedApps.contains(selKey)) {
                        binding.ivEnlargedSelectCircle.setImageResource(R.drawable.ic_edit_circle_selected)
                    } else {
                        binding.ivEnlargedSelectCircle.setImageResource(R.drawable.ic_edit_circle_unselected)
                    }
                    binding.ivEnlargedSelectCircle.setOnClickListener {
                        onSelectToggle?.invoke(item)
                    }
                }
            } else {
                binding.layoutEnlargedFolder.visibility = View.GONE
                binding.iconContainer.visibility = View.VISIBLE
                binding.appLabel.visibility = if (showLabels) View.VISIBLE else View.GONE
                binding.appLabel.text = item.label
                // setTextSize безусловно дёргает requestLayout() на каждой ячейке
                val labelSp = if (itemHeight > 0 && itemHeight < 72 * density) 10.5f else 11.5f
                if (binding.appLabel.textSize != labelSp) binding.appLabel.textSize = labelSp

                if (item.isFolder) {
                    // Превью папки 2x2
                    binding.appIcon.visibility = View.GONE
                    binding.layoutFolderPreview.visibility = View.VISIBLE
                    val activity = binding.root.context as? com.naua_morphix_launcher.app.MainActivity
                    if (activity != null) {
                        binding.layoutFolderPreview.setGlassEnabled(activity.currentSettings.isGlassEnabled)
                    }
                    val miniIcons = folderMiniViews
                    val miniIconSize = if (finalIconSize < 44 * density) (14 * density).toInt() else (19 * density).toInt()
                    for (i in 0..3) {
                        val miniIconView = miniIcons[i]
                        val lp = miniIconView.layoutParams
                        if (lp.width != miniIconSize || lp.height != miniIconSize) {
                            lp.width = miniIconSize
                            lp.height = miniIconSize
                            miniIconView.layoutParams = lp
                        }
                        if (i < item.folderApps.size) {
                            miniIconView.visibility = View.VISIBLE
                            miniIconView.setImageDrawable(item.folderApps[i].icon)
                        } else {
                            miniIconView.visibility = View.INVISIBLE
                        }
                    }
                } else {
                    // Обычное приложение
                    binding.layoutFolderPreview.visibility = View.GONE
                    binding.appIcon.visibility = View.VISIBLE
                    binding.appIcon.setImageDrawable(item.icon)

                    // Динамическое аппаратное применение формы иконки (Сквиркл Xiaomi)
                    binding.appIcon.shapeAppearanceModel = getShapeModel(iconShape)

                    // Масштаб иконки внутри контейнера нормализован
                    binding.appIcon.scaleX = 1.0f
                    binding.appIcon.scaleY = 1.0f
                }

                // Xiaomi Edit Mode Selection
                binding.ivEnlargedSelectCircle.visibility = View.GONE
                binding.ivSelectCircle.visibility = if (isEditMode && !item.isEmpty && !item.isWidget) View.VISIBLE else View.GONE
                if (binding.ivSelectCircle.visibility == View.VISIBLE) {
                    val selKey = if (item.isFolder) "folder:${item.folderId}" else item.packageName
                    if (selectedApps.contains(selKey)) {
                        binding.ivSelectCircle.setImageResource(R.drawable.ic_edit_circle_selected)
                    } else {
                        binding.ivSelectCircle.setImageResource(R.drawable.ic_edit_circle_unselected)
                    }
                    binding.ivSelectCircle.setOnClickListener {
                        onSelectToggle?.invoke(item)
                    }
                }
            }

            // Бейджик Второго пространства / Клона приложения
            binding.cloneBadge.visibility = if (item.isSecondSpace) View.VISIBLE else View.GONE

            renderBadge(item)

            downRawX = 0f
            downRawY = 0f
            binding.root.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        if (smoothAnimations) {
                            v.animate()
                                .scaleX(0.88f)
                                .scaleY(0.88f)
                                .setDuration(120)
                                .setInterpolator(android.view.animation.DecelerateInterpolator())
                                .start()
                        }
                        pendingLongPress = Runnable {
                            markLongPressConsumed()
                            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                            if (onAppStartDrag != null) {
                                onAppStartDrag.invoke(item, binding.root, downRawX, downRawY)
                            } else {
                                onAppLongClick?.invoke(item, binding.root, this)
                            }
                        }
                        TOUCH_HANDLER.postDelayed(pendingLongPress!!, 200)
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val diffX = Math.abs(event.rawX - downRawX)
                        val diffY = Math.abs(event.rawY - downRawY)
                        if (diffX > 10 * v.resources.displayMetrics.density || diffY > 10 * v.resources.displayMetrics.density) {
                            if (smoothAnimations) {
                                v.animate()
                                    .scaleX(1.0f)
                                    .scaleY(1.0f)
                                    .setDuration(150)
                                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                                    .start()
                            } else {
                                v.scaleX = 1.0f
                                v.scaleY = 1.0f
                            }
                            cancelPendingCallbacks()
                        }
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        if (smoothAnimations) {
                            v.animate()
                                .scaleX(1.0f)
                                .scaleY(1.0f)
                                .setDuration(220)
                                .setInterpolator(android.view.animation.OvershootInterpolator(2.2f))
                                .start()
                        } else {
                            v.scaleX = 1.0f
                            v.scaleY = 1.0f
                        }
                        cancelPendingCallbacks()
                        if (!consumeTapAfterLongPress()) {
                            val diffX = Math.abs(event.rawX - downRawX)
                            val diffY = Math.abs(event.rawY - downRawY)
                            if (diffX <= 10 * v.resources.displayMetrics.density && diffY <= 10 * v.resources.displayMetrics.density) {
                                v.performClick()
                                onAppClickWithView?.invoke(item, v) ?: onAppClick(item)
                            }
                        }
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        if (smoothAnimations) {
                            v.animate()
                                .scaleX(1.0f)
                                .scaleY(1.0f)
                                .setDuration(150)
                                .setInterpolator(android.view.animation.DecelerateInterpolator())
                                .start()
                        } else {
                            v.scaleX = 1.0f
                            v.scaleY = 1.0f
                        }
                        cancelPendingCallbacks()
                    }
                }
                true
            }
            binding.root.setOnClickListener(null)
            binding.root.setOnLongClickListener(null)
        }

        private fun renderBadge(item: AppItem) {
            val unreadCount = badgeCounts[item.packageName] ?: 0
            if (unreadCount > 0) {
                val wasGone = binding.notificationBadge.visibility != View.VISIBLE
                binding.notificationBadge.visibility = View.VISIBLE
                binding.notificationBadge.text = if (unreadCount > 99) "99+" else unreadCount.toString()
                if (smoothAnimations && wasGone) {
                    binding.notificationBadge.scaleX = 0f
                    binding.notificationBadge.scaleY = 0f
                    binding.notificationBadge.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(180)
                        .setInterpolator(android.view.animation.OvershootInterpolator(1.5f))
                        .start()
                } else {
                    binding.notificationBadge.scaleX = 1.0f
                    binding.notificationBadge.scaleY = 1.0f
                }
            } else {
                binding.notificationBadge.visibility = View.GONE
            }
        }

        fun updateEditMode(editMode: Boolean) {
            val item = items.getOrNull(bindingAdapterPosition) ?: return
            val isEnlarged = item.isFolder && (item.folderSize == "ENLARGED" || item.folderSize == "XXL")
            val shouldShow = editMode && !item.isEmpty && !item.isWidget

            val targetCircle = if (isEnlarged) binding.ivEnlargedSelectCircle else binding.ivSelectCircle
            val otherCircle = if (isEnlarged) binding.ivSelectCircle else binding.ivEnlargedSelectCircle
            otherCircle.visibility = View.GONE

            val wasVisible = targetCircle.visibility == View.VISIBLE
            targetCircle.visibility = if (shouldShow) View.VISIBLE else View.GONE
            if (targetCircle.visibility == View.VISIBLE) {
                val selKey = if (item.isFolder) "folder:${item.folderId}" else item.packageName
                if (selectedApps.contains(selKey)) {
                    targetCircle.setImageResource(R.drawable.ic_edit_circle_selected)
                } else {
                    targetCircle.setImageResource(R.drawable.ic_edit_circle_unselected)
                }
                targetCircle.setOnClickListener {
                    onSelectToggle?.invoke(item)
                }
                if (!wasVisible) {
                    if (smoothAnimations) {
                        targetCircle.scaleX = 0f
                        targetCircle.scaleY = 0f
                        targetCircle.animate()
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .setDuration(160)
                            .setInterpolator(android.view.animation.OvershootInterpolator(1.4f))
                            .start()
                    } else {
                        targetCircle.scaleX = 1.0f
                        targetCircle.scaleY = 1.0f
                    }
                } else {
                    targetCircle.scaleX = 1.0f
                    targetCircle.scaleY = 1.0f
                }
            }
        }

        fun setFolderDropHighlight(highlighted: Boolean, draggedItem: AppItem? = null) {
            val item = items.getOrNull(bindingAdapterPosition) ?: return
            binding.folderDropHighlight.visibility = if (highlighted) View.VISIBLE else View.GONE
            
            if (highlighted) {
                if (smoothAnimations) {
                    binding.root.animate().scaleX(1.18f).scaleY(1.18f).setDuration(150).start()
                } else {
                    binding.root.scaleX = 1.05f
                    binding.root.scaleY = 1.05f
                }
                
                // Показывать призрака папки, если наводим приложение на приложение
                if (draggedItem != null && !item.isFolder && !draggedItem.isFolder) {
                    binding.appIcon.visibility = View.GONE
                    binding.layoutFolderPreview.visibility = View.VISIBLE
                    binding.folderMiniIcon1.visibility = View.VISIBLE
                    binding.folderMiniIcon1.setImageDrawable(item.icon)
                    binding.folderMiniIcon2.visibility = View.VISIBLE
                    binding.folderMiniIcon2.setImageDrawable(draggedItem.icon)
                    binding.folderMiniIcon3.visibility = View.INVISIBLE
                    binding.folderMiniIcon4.visibility = View.INVISIBLE
                }
            } else {
                if (smoothAnimations) {
                    binding.root.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                } else {
                    binding.root.scaleX = 1.0f
                    binding.root.scaleY = 1.0f
                }
                
                // Возвращаем как было
                if (!item.isFolder) {
                    binding.appIcon.visibility = View.VISIBLE
                    binding.layoutFolderPreview.visibility = View.GONE
                } else {
                    binding.appIcon.visibility = View.GONE
                    binding.layoutFolderPreview.visibility = View.VISIBLE
                }
            }
        }

        fun setSwapHighlight(highlighted: Boolean) {
            if (bindingAdapterPosition !in items.indices) return
            if (highlighted) {
                if (smoothAnimations) {
                    binding.root.animate().scaleX(0.85f).scaleY(0.85f).alpha(0.6f).setDuration(150).start()
                } else {
                    binding.root.scaleX = 0.9f
                    binding.root.scaleY = 0.9f
                    binding.root.alpha = 0.8f
                }
            } else {
                if (smoothAnimations) {
                    binding.root.animate().scaleX(1.0f).scaleY(1.0f).alpha(1.0f).setDuration(150).start()
                } else {
                    binding.root.scaleX = 1.0f
                    binding.root.scaleY = 1.0f
                    binding.root.alpha = 1.0f
                }
            }
        }

        fun setEmptyDropGhost(draggedItem: AppItem?) {
            val item = items.getOrNull(bindingAdapterPosition) ?: return
            if (!item.isEmpty) return
            
            if (draggedItem != null && !draggedItem.isWidget) {
                binding.appIcon.setImageDrawable(draggedItem.icon)
                binding.appIcon.shapeAppearanceModel = getShapeModel(iconShape)
                binding.appIcon.scaleX = iconScale
                binding.appIcon.scaleY = iconScale
                binding.appIcon.alpha = 0.4f
                binding.appIcon.visibility = View.VISIBLE
                
                binding.appLabel.text = draggedItem.label
                binding.appLabel.alpha = 0.4f
                binding.appLabel.visibility = if (showLabels) View.VISIBLE else View.GONE
            } else {
                binding.appIcon.visibility = View.INVISIBLE
                binding.appIcon.alpha = 1.0f
                binding.appLabel.visibility = View.GONE
                binding.appLabel.alpha = 1.0f
            }
        }
    }

    inner class WidgetViewHolder(private val binding: ItemWidgetCellBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private var attachedWidgetId: Int? = null
        private var attachedHostView: View? = null
        private var widgetDownRawX = 0f
        private var widgetDownRawY = 0f

        fun applyDragAlpha(item: AppItem) {
            val draggedKey = draggedItemKey
            val isDragged = draggedKey != null && identityOf(item) == draggedKey
            val target = if (isDragged) 0f else 1f
            if (binding.root.alpha != target) binding.root.alpha = target
        }

        /**
         * Снимает ссылку на переработанный холдер. Замыкание onWidgetLongClick
         * удерживало binding целиком, а сам hostView живёт в кэше активности —
         * то есть переработанный ViewHolder не мог освободиться.
         */
        fun detach() {
            (attachedHostView as? MorphixAppWidgetHostView)?.onWidgetLongClick = null
            attachedHostView?.setOnTouchListener(null)
            attachedHostView?.setOnLongClickListener(null)
            attachedHostView = null
            attachedWidgetId = null
        }

        fun bind(item: AppItem) {
            val spanY = item.widgetSpanY.coerceAtLeast(1)
            if (itemHeight > 0) {
                val totalHeight = itemHeight * spanY
                val lp = binding.root.layoutParams
                if (lp != null && lp.height != totalHeight) {
                    lp.height = totalHeight
                    binding.root.layoutParams = lp
                }
            }

            val widgetId = item.widgetId

            // AppWidgetHostView переинфлит дерево RemoteViews при каждом
            // attach/detach. Раньше bind делал removeAllViews + addView безусловно,
            // то есть виджет переподключался на каждом обновлении данных.
            if (widgetId != null
                && widgetId == attachedWidgetId
                && attachedHostView?.parent === binding.widgetHostHolder
            ) {
                return
            }

            detach()
            binding.widgetHostHolder.removeAllViews()

            if (widgetId != null && onGetWidgetHostView != null) {
                val hostView = onGetWidgetHostView.invoke(widgetId, item)
                if (hostView != null) {
                    (hostView.parent as? ViewGroup)?.removeView(hostView)
                    val lp = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    binding.widgetHostHolder.addView(hostView, lp)
                    attachedWidgetId = widgetId
                    attachedHostView = hostView

                    (hostView as? MorphixAppWidgetHostView)?.onWidgetLongClick = { rawX, rawY ->
                        if (onAppStartDrag != null) {
                            onAppStartDrag.invoke(item, binding.root, rawX, rawY)
                        } else {
                            onAppLongClick?.invoke(item, binding.root, this)
                        }
                    }

                    hostView.setOnTouchListener { v, event ->
                        when (event.actionMasked) {
                            android.view.MotionEvent.ACTION_DOWN -> {
                                widgetDownRawX = event.rawX
                                widgetDownRawY = event.rawY
                                if (smoothAnimations) {
                                    v.animate().scaleX(0.98f).scaleY(0.98f).setDuration(120).start()
                                }
                            }
                            android.view.MotionEvent.ACTION_UP,
                            android.view.MotionEvent.ACTION_CANCEL -> {
                                if (smoothAnimations) {
                                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(180).setInterpolator(android.view.animation.OvershootInterpolator(1.5f)).start()
                                } else {
                                    v.scaleX = 1.0f
                                    v.scaleY = 1.0f
                                }
                            }
                        }
                        false
                    }

                    hostView.setOnLongClickListener {
                        if (onAppStartDrag != null) {
                            onAppStartDrag.invoke(item, binding.root, widgetDownRawX, widgetDownRawY)
                        } else {
                            onAppLongClick?.invoke(item, binding.root, this)
                        }
                        true
                    }
                }
            }

            binding.root.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        widgetDownRawX = event.rawX
                        widgetDownRawY = event.rawY
                        if (smoothAnimations) {
                            v.animate().scaleX(0.98f).scaleY(0.98f).setDuration(120).start()
                        }
                    }
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        if (smoothAnimations) {
                            v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(180).setInterpolator(android.view.animation.OvershootInterpolator(1.5f)).start()
                        } else {
                            v.scaleX = 1.0f
                            v.scaleY = 1.0f
                        }
                    }
                }
                false
            }

            binding.root.setOnLongClickListener {
                if (onAppStartDrag != null) {
                    onAppStartDrag.invoke(item, binding.root, widgetDownRawX, widgetDownRawY)
                } else {
                    onAppLongClick?.invoke(item, binding.root, this)
                }
                true
            }
        }
    }

    companion object {
        const val VIEW_TYPE_APP = 0
        const val VIEW_TYPE_WIDGET = 1

        const val PAYLOAD_EDIT_MODE = "EDIT_MODE"
        const val PAYLOAD_DRAG_STATE = "DRAG_STATE"
        const val PAYLOAD_BADGE = "BADGE"
        const val PAYLOAD_CONFIG = "CONFIG"

        /** Один Handler на адаптер. Раньше создавался новый Handler на каждый bind. */
        private val TOUCH_HANDLER = Handler(Looper.getMainLooper())

        /** ShapeAppearanceModel кэшируется: 4 значения enum, а пересборка формы
         *  заставляла Material пересоздавать маску Path при отрисовке. */
        private val shapeModelCache = java.util.concurrent.ConcurrentHashMap<IconShape, ShapeAppearanceModel>()

        /**
         * Кэшированные ShapeAppearanceModel: 4 значения enum, а пересборка
         * формы заставляла Material пересоздавать маску Path при отрисовке.
         */
        fun getShapeModel(shape: IconShape): ShapeAppearanceModel =
            shapeModelCache.getOrPut(shape) {
                when (shape) {
                    IconShape.CIRCLE -> {
                        ShapeAppearanceModel.builder()
                            .setAllCornerSizes(RelativeCornerSize(0.5f))
                            .build()
                    }
                    IconShape.SQUIRCLE, IconShape.ORIGINAL -> {
                        // Фирменный сквиркл Xiaomi HyperOS
                        ShapeAppearanceModel.builder()
                            .setAllCornerSizes(RelativeCornerSize(0.22f))
                            .build()
                    }
                    IconShape.ROUNDED_SQUARE -> {
                        ShapeAppearanceModel.builder()
                            .setAllCornerSizes(RelativeCornerSize(0.16f))
                            .build()
                    }
                    IconShape.TEARDROP -> {
                        ShapeAppearanceModel.builder()
                            .setTopLeftCornerSize(RelativeCornerSize(0.5f))
                            .setTopRightCornerSize(RelativeCornerSize(0.5f))
                            .setBottomLeftCornerSize(RelativeCornerSize(0.5f))
                            .setBottomRightCornerSize(RelativeCornerSize(0.08f))
                            .build()
                    }
                }
            }
    }
}
