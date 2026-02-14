package com.family.dialer

import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.family.dialer.adapter.ContactManageAdapter
import com.family.dialer.data.Contact

class ManageActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ContactManageAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manage)

        recyclerView = findViewById(R.id.recyclerManage)
        val btnBack: ImageButton = findViewById(R.id.btnBack)
        val btnAdd: ImageButton = findViewById(R.id.btnAdd)

        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = ContactManageAdapter(
            onEditClick = { contact ->
                val intent = Intent(this, AddEditContactActivity::class.java)
                intent.putExtra("contact_id", contact.id)
                startActivity(intent)
            },
            onDeleteClick = { contact ->
                showDeleteConfirm(contact)
            }
        )
        recyclerView.adapter = adapter

        // 观察数据库变化
        val db = (application as App).database
        db.contactDao().getAll().observe(this) { contacts ->
            adapter.submitList(contacts)
        }

        // 返回
        btnBack.setOnClickListener { finish() }

        // 添加联系人
        btnAdd.setOnClickListener {
            val intent = Intent(this, AddEditContactActivity::class.java)
            startActivity(intent)
        }
    }

    private fun showDeleteConfirm(contact: Contact) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.confirm_delete_title))
            .setMessage(getString(R.string.confirm_delete_msg, contact.name))
            .setPositiveButton(getString(R.string.btn_delete)) { _, _ ->
                Thread {
                    (application as App).database.contactDao().delete(contact)
                    runOnUiThread {
                        Toast.makeText(this, getString(R.string.toast_deleted), Toast.LENGTH_SHORT).show()
                    }
                }.start()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }
}
