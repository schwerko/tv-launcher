package com.azura.tvlauncher

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class AppAdapter(
    private val onClick: (AppInfo) -> Unit,
    private val onLongClick: (AppInfo) -> Unit,
) : ListAdapter<AppInfo, AppAdapter.Holder>(DIFF) {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val banner: ImageView = view.findViewById(R.id.banner)
        val icon: ImageView = view.findViewById(R.id.icon)
        val label: TextView = view.findViewById(R.id.label)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        val holder = Holder(view)
        view.setOnFocusChangeListener { v, focused ->
            val scale = if (focused) 1.08f else 1f
            v.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
            holder.label.isSelected = focused
        }
        view.setOnClickListener { (it.tag as? AppInfo)?.let(onClick) }
        view.setOnLongClickListener { v ->
            (v.tag as? AppInfo)?.let(onLongClick)
            true
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val app = getItem(position)
        holder.itemView.tag = app
        holder.label.text = app.label
        if (app.banner != null) {
            holder.banner.setImageDrawable(app.banner)
            holder.icon.visibility = View.GONE
        } else {
            holder.banner.setImageDrawable(null)
            holder.icon.setImageDrawable(app.icon)
            holder.icon.visibility = View.VISIBLE
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AppInfo>() {
            override fun areItemsTheSame(a: AppInfo, b: AppInfo) = a.packageName == b.packageName
            override fun areContentsTheSame(a: AppInfo, b: AppInfo) =
                a.label == b.label && (a.banner == null) == (b.banner == null)
        }
    }
}
