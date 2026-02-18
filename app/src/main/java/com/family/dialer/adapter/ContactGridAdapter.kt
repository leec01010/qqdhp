package com.family.dialer.adapter

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.family.dialer.R
import com.family.dialer.data.Contact

class ContactGridAdapter(
    private val onContactClick: (Contact) -> Unit
) : ListAdapter<Contact, ContactGridAdapter.ViewHolder>(ContactDiffCallback()) {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvAvatar: TextView = view.findViewById(R.id.tvAvatar)
        val tvName: TextView = view.findViewById(R.id.tvName)
        val card: View = view.findViewById(R.id.cardContact)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_contact_grid, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val contact = getItem(position)

        // 设置头像（取名字第一个字）
        val initial = if (contact.name.isNotEmpty()) contact.name.first().toString() else "?"
        holder.tvAvatar.text = initial

        // 圆形背景色
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(contact.color)
        holder.tvAvatar.background = bg

        // 名字
        holder.tvName.text = contact.name

        // 点击跳转详情页
        holder.card.setOnClickListener { onContactClick(contact) }
    }

    class ContactDiffCallback : DiffUtil.ItemCallback<Contact>() {
        override fun areItemsTheSame(oldItem: Contact, newItem: Contact) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Contact, newItem: Contact) = oldItem == newItem
    }
}
