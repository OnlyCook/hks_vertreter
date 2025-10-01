package com.thecooker.vertretungsplaner.ui.moodle

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
    private val onTabClick: (Int) -> Unit,
    private val onTabClose: (Int) -> Unit,
    private val onTabPin: (Int) -> Unit
) : RecyclerView.Adapter<TabAdapter.TabViewHolder>() {

    class TabViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView = view.findViewById(R.id.tabThumbnail)
        val title: TextView = view.findViewById(R.id.tabTitle)
        val closeButton: ImageButton = view.findViewById(R.id.btnCloseTab)
        val pinButton: ImageButton = view.findViewById(R.id.btnPinTab)
        val container: MaterialCardView = view.findViewById(R.id.tabContainer)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tab, parent, false)
        return TabViewHolder(view)
    }

    override fun onBindViewHolder(holder: TabViewHolder, position: Int) {
        val tab = tabs[position]
        val isCurrentTab = position == currentTabIndex

        holder.title.text = buildString {
            append(tab.title.take(30))
            append(if (tab.title.length > 30) "..." else "")
        }

        if (tab.thumbnail != null) {
            holder.thumbnail.setImageBitmap(tab.thumbnail)
        } else {
            holder.thumbnail.setImageResource(R.drawable.ic_tab_placeholder)
        }

        holder.container.strokeWidth = if (isCurrentTab) {
            (4 * holder.itemView.resources.displayMetrics.density).toInt()
        } else {
            0
        }

        holder.container.strokeColor = if (isCurrentTab) {
            holder.itemView.context.getThemeColor(R.attr.settingsColorPrimary)
        } else {
            android.graphics.Color.TRANSPARENT
        }

        holder.pinButton.setImageResource(
            if (tab.isPinned) R.drawable.ic_pin_filled else R.drawable.ic_pin_outline
        )

        holder.pinButton.isEnabled = position > 0
        holder.pinButton.alpha = if (position > 0) 1f else 0.3f

        holder.closeButton.setOnClickListener {
            onTabClose(position)
        }

        holder.pinButton.setOnClickListener {
            if (position > 0) {
                onTabPin(position)
            }
        }

        holder.itemView.setOnClickListener {
            onTabClick(position)
        }
    }

    override fun getItemCount() = tabs.size
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