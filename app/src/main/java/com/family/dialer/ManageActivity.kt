package com.family.dialer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.family.dialer.adapter.ContactManageAdapter
import com.family.dialer.data.Contact

class ManageActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ContactManageAdapter
    private val READ_CONTACTS_CODE = 200

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manage)

        recyclerView = findViewById(R.id.recyclerManage)
        val btnBack: Button = findViewById(R.id.btnBack)
        val btnAdd: Button = findViewById(R.id.btnAdd)
        val btnImport: Button = findViewById(R.id.btnImport)

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

        // 从手机导入联系人
        btnImport.setOnClickListener {
            requestImportContacts()
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

    /**
     * 请求读取联系人权限并导入
     */
    private fun requestImportContacts() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            showImportPicker()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_CONTACTS),
                READ_CONTACTS_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == READ_CONTACTS_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showImportPicker()
            } else {
                Toast.makeText(this, "需要联系人权限才能导入", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 读取手机联系人并弹窗让用户选择导入哪些
     */
    private fun showImportPicker() {
        Thread {
            val phoneContacts = readPhoneContacts()
            runOnUiThread {
                if (phoneContacts.isEmpty()) {
                    Toast.makeText(this, "手机通讯录是空的", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }

                val names = phoneContacts.map { "${it.first}  ${it.second}" }.toTypedArray()
                val checked = BooleanArray(names.size) { false }
                var allSelected = false

                val dialog = AlertDialog.Builder(this)
                    .setTitle("选择要导入的联系人")
                    .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                        checked[which] = isChecked
                    }
                    .setPositiveButton("导入") { _, _ ->
                        importSelected(phoneContacts, checked)
                    }
                    .setNegativeButton("取消", null)
                    .setNeutralButton("全选", null)
                    .create()

                dialog.show()

                // 用 setOnClickListener 防止点全选后弹窗关闭
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    allSelected = !allSelected
                    val listView = dialog.listView
                    for (i in names.indices) {
                        checked[i] = allSelected
                        listView.setItemChecked(i, allSelected)
                    }
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).text =
                        if (allSelected) "取消全选" else "全选"
                }
            }
        }.start()
    }

    /**
     * 从 ContentProvider 读取手机联系人
     */
    private fun readPhoneContacts(): List<Pair<String, String>> {
        val contacts = mutableListOf<Pair<String, String>>()
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )
        cursor?.use {
            val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val phoneIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                val name = it.getString(nameIndex) ?: continue
                val phone = it.getString(phoneIndex) ?: continue
                contacts.add(Pair(name, phone.replace("\\s+".toRegex(), "")))
            }
        }
        return contacts.distinctBy { it.second } // 按号码去重
    }

    /**
     * 将选中的联系人写入本 App 数据库
     */
    private fun importSelected(phoneContacts: List<Pair<String, String>>, checked: BooleanArray) {
        val presetColors = intArrayOf(
            ContextCompat.getColor(this, R.color.avatar_red),
            ContextCompat.getColor(this, R.color.avatar_pink),
            ContextCompat.getColor(this, R.color.avatar_purple),
            ContextCompat.getColor(this, R.color.avatar_blue),
            ContextCompat.getColor(this, R.color.avatar_teal),
            ContextCompat.getColor(this, R.color.avatar_green),
            ContextCompat.getColor(this, R.color.avatar_orange),
            ContextCompat.getColor(this, R.color.avatar_brown)
        )

        Thread {
            val dao = (application as App).database.contactDao()
            var count = 0
            phoneContacts.forEachIndexed { index, (name, phone) ->
                if (checked[index]) {
                    val color = presetColors[count % presetColors.size]
                    dao.insert(Contact(name = name, phone = phone, color = color))
                    count++
                }
            }
            runOnUiThread {
                Toast.makeText(this, "已导入 $count 位联系人", Toast.LENGTH_SHORT).show()
                // 导入后直接返回主页面
                val intent = Intent(this, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                startActivity(intent)
                finish()
            }
        }.start()
    }
}
