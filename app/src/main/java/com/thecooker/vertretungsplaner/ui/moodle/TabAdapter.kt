package com.thecooker.vertretungsplaner.ui.moodle

import android.content.ClipData
import android.content.Context
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.thecooker.vertretungsplaner.R
import com.google.android.material.card.MaterialCardView

class TabAdapter(
    private val tabs: List<MoodleFragment.TabInfo>,
    private var currentTabIndex: Int,
    private val isCompactLayout: Boolean,
    private val itemWidth: Int,
    private val itemHeight: Int,
    private val onTabClick: (Int) -> Unit,
    private val onTabClose: (Int) -> Unit,
    private val onTabPin: (Int) -> Unit,
    private val onTabDragStart: (Int) -> Unit,
    private val onTabDragEnd: (Int, Float, Float) -> Unit,
    private val onTabMove: (Int, Int) -> Unit,
    private val onTabDragUpdate: (Int, Float, Float) -> Unit,
    private val onNewTabClick: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_TAB = 0
        private const val TYPE_NEW_TAB = 1
    }

    private var draggedPosition = -1
    private var targetPosition = -1

    class TabViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView = view.findViewById(R.id.tabThumbnail)
        val title: TextView = view.findViewById(R.id.tabTitle)
        val closeButton: ImageButton? = view.findViewById(R.id.btnCloseTab)
        val pinButton: ImageButton? = view.findViewById(R.id.btnPinTab)
        val container: MaterialCardView = view.findViewById(R.id.tabContainer)
        val defaultIndicator: View? = view.findViewById(R.id.defaultTabIndicator)
    }

    class NewTabViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val container: MaterialCardView = view.findViewById(R.id.newTabContainer)
    }

    override fun getItemViewType(position: Int): Int {
        return if (position == tabs.size) TYPE_NEW_TAB else TYPE_TAB
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_NEW_TAB) {
            val layoutId = if (isCompactLayout) R.layout.item_new_tab_compact else R.layout.item_new_tab
            val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
            view.layoutParams.width = itemWidth
            view.layoutParams.height = itemHeight
            NewTabViewHolder(view)
        } else {
            val layoutId = if (isCompactLayout) R.layout.item_tab_compact else R.layout.item_tab
            val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
            view.layoutParams.width = itemWidth
            view.layoutParams.height = itemHeight
            TabViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is NewTabViewHolder) {
            holder.container.setOnClickListener {
                onNewTabClick()
            }
            return
        }

        val tabHolder = holder as TabViewHolder
        val actualPosition = holder.adapterPosition
        if (actualPosition == RecyclerView.NO_POSITION || actualPosition >= tabs.size) return

        val tab = tabs[actualPosition]
        val isCurrentTab = actualPosition == currentTabIndex
        val isDefaultTab = tab.isDefault

        tabHolder.title.text = buildString {
            append(tab.title.take(if (isCompactLayout) 15 else 30))
            if (tab.title.length > (if (isCompactLayout) 15 else 30)) append("...")
        }

        if (tab.thumbnail != null) {
            tabHolder.thumbnail.setImageBitmap(tab.thumbnail)
        } else {
            tabHolder.thumbnail.setImageResource(R.drawable.ic_tab_placeholder)
        }

        tabHolder.container.strokeWidth = if (isCurrentTab) {
            (3 * holder.itemView.resources.displayMetrics.density).toInt()
        } else {
            0
        }

        tabHolder.container.strokeColor = if (isCurrentTab) {
            holder.itemView.context.getThemeColor(R.attr.settingsColorPrimary)
        } else {
            android.graphics.Color.TRANSPARENT
        }

        tabHolder.defaultIndicator?.visibility = if (isDefaultTab) View.VISIBLE else View.GONE

        if (!isCompactLayout && !isDefaultTab) {
            tabHolder.pinButton?.apply {
                visibility = View.VISIBLE
                setImageResource(
                    if (tab.isPinned) R.drawable.ic_pin_filled else R.drawable.ic_pin_outline
                )
                setOnClickListener {
                    onTabPin(actualPosition)
                }
            }

            tabHolder.closeButton?.apply {
                visibility = View.VISIBLE
                setOnClickListener {
                    tabHolder.itemView.animate()
                        .alpha(0f)
                        .scaleX(0.85f)
                        .scaleY(0.85f)
                        .translationX(tabHolder.itemView.width.toFloat() * 0.3f)
                        .setDuration(150)
                        .withEndAction {
                            tabHolder.itemView.alpha = 1f
                            tabHolder.itemView.scaleX = 1f
                            tabHolder.itemView.scaleY = 1f
                            tabHolder.itemView.translationX = 0f

                            onTabClose(actualPosition)
                        }
                        .start()
                }
            }
        } else {
            tabHolder.pinButton?.visibility = View.GONE
            tabHolder.closeButton?.visibility = View.GONE
        }

        if (!isDefaultTab && !isCompactLayout) {
            tabHolder.container.setOnLongClickListener { view ->
                draggedPosition = actualPosition
                onTabDragStart(actualPosition)

                val clipData = ClipData.newPlainText("", "")
                val shadowBuilder = View.DragShadowBuilder(view)
                view.startDragAndDrop(clipData, shadowBuilder, view, 0)
                true
            }

            tabHolder.container.setOnDragListener { v, event ->
                val targetPos = holder.adapterPosition
                if (targetPos == RecyclerView.NO_POSITION || targetPos >= tabs.size) return@setOnDragListener false

                val targetTab = tabs[targetPos]

                when (event.action) {
                    android.view.DragEvent.ACTION_DRAG_LOCATION -> {
                        onTabDragUpdate(actualPosition, event.x, event.y)
                        true
                    }
                    android.view.DragEvent.ACTION_DRAG_ENTERED -> {
                        if (draggedPosition != -1 && draggedPosition < tabs.size &&
                            !targetTab.isDefault && draggedPosition != targetPos) {
                            targetPosition = targetPos
                            v.animate().scaleX(1.05f).scaleY(1.05f).setDuration(150).start()
                        }
                        true
                    }
                    android.view.DragEvent.ACTION_DRAG_EXITED -> {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                        true
                    }
                    android.view.DragEvent.ACTION_DROP -> {
                        if (draggedPosition != -1 && draggedPosition < tabs.size &&
                            targetPosition != -1 && targetPosition < tabs.size &&
                            draggedPosition != targetPosition && !targetTab.isDefault) {
                            onTabMove(draggedPosition, targetPosition)
                        }
                        v.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                        true
                    }
                    android.view.DragEvent.ACTION_DRAG_ENDED -> {
                        if (draggedPosition != -1 && draggedPosition < tabs.size) {
                            onTabDragEnd(draggedPosition, event.x, event.y)
                        }
                        draggedPosition = -1
                        targetPosition = -1
                        true
                    }
                    else -> true
                }
            }
        } else {
            tabHolder.container.setOnLongClickListener(null)
            tabHolder.container.setOnDragListener(null)
        }

        tabHolder.container.setOnClickListener {
            onTabClick(actualPosition)
        }
    }

    override fun getItemCount() = tabs.size + if (tabs.size < 10) 1 else 0

    private fun Context.getThemeColor(@AttrRes attrRes: Int): Int {
        val typedValue = TypedValue()
        val theme = theme
        theme.resolveAttribute(attrRes, typedValue, true)
        return if (typedValue.resourceId != 0) {
            ContextCompat.getColor(this, typedValue.resourceId)
        } else {
            typedValue.data
        }
    }
}