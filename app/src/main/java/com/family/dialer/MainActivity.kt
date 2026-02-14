package com.family.dialer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.family.dialer.adapter.ContactGridAdapter
import com.family.dialer.data.Contact

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var btnManage: Button
    private lateinit var adapter: ContactGridAdapter

    private val CALL_PERMISSION_CODE = 100
    private var pendingCallContact: Contact? = null

    // 长按计时
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var longPressTriggered = false
    private val LONG_PRESS_DURATION = 3000L // 3秒

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.recyclerContacts)
        tvEmpty = findViewById(R.id.tvEmpty)
        btnManage = findViewById(R.id.btnManage)

        // 设置网格布局 (2列)
        recyclerView.layoutManager = GridLayoutManager(this, 2)

        adapter = ContactGridAdapter { contact ->
            makeCall(contact)
        }
        recyclerView.adapter = adapter

        // 观察数据库变化
        val db = (application as App).database
        db.contactDao().getAll().observe(this) { contacts ->
            adapter.submitList(contacts)
            tvEmpty.visibility = if (contacts.isEmpty()) View.VISIBLE else View.GONE
            recyclerView.visibility = if (contacts.isEmpty()) View.GONE else View.VISIBLE
        }

        // 长按 3 秒进入管理界面
        setupLongPressManage()
    }

    private fun setupLongPressManage() {
        btnManage.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    longPressTriggered = false
                    longPressHandler.postDelayed({
                        longPressTriggered = true
                        // 触发进入管理界面
                        val intent = Intent(this@MainActivity, ManageActivity::class.java)
                        startActivity(intent)
                    }, LONG_PRESS_DURATION)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressHandler.removeCallbacksAndMessages(null)
                    if (!longPressTriggered) {
                        Toast.makeText(this, getString(R.string.long_press_hint), Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun makeCall(contact: Contact) {
        pendingCallContact = contact
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            == PackageManager.PERMISSION_GRANTED
        ) {
            dialPhone(contact)
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CALL_PHONE),
                CALL_PERMISSION_CODE
            )
        }
    }

    private fun dialPhone(contact: Contact) {
        val intent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:${contact.phone}")
        }
        try {
            startActivity(intent)
            Toast.makeText(this, getString(R.string.toast_calling, contact.name), Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(this, getString(R.string.toast_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CALL_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                pendingCallContact?.let { dialPhone(it) }
            } else {
                Toast.makeText(this, getString(R.string.toast_permission_denied), Toast.LENGTH_LONG).show()
            }
        }
    }
}
