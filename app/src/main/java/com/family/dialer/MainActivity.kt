package com.family.dialer

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.family.dialer.adapter.ContactGridAdapter

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var btnManage: Button
    private lateinit var adapter: ContactGridAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.recyclerContacts)
        tvEmpty = findViewById(R.id.tvEmpty)
        btnManage = findViewById(R.id.btnManage)

        // 设置网格布局 (2列)
        recyclerView.layoutManager = GridLayoutManager(this, 2)

        adapter = ContactGridAdapter { contact ->
            // 点击联系人 → 跳转详情页
            val intent = Intent(this, ContactDetailActivity::class.java)
            intent.putExtra("contact_id", contact.id)
            startActivity(intent)
        }
        recyclerView.adapter = adapter

        // 观察数据库变化
        val db = (application as App).database
        db.contactDao().getAll().observe(this) { contacts ->
            adapter.submitList(contacts)
            tvEmpty.visibility = if (contacts.isEmpty()) View.VISIBLE else View.GONE
            recyclerView.visibility = if (contacts.isEmpty()) View.GONE else View.VISIBLE
        }

        // 点击直接进入管理界面
        btnManage.setOnClickListener {
            val intent = Intent(this, ManageActivity::class.java)
            startActivity(intent)
        }
    }
}
