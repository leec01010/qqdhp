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
import com.family.dialer.flow.FlowEditorActivity

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

        // 设置微信拨打流程
        findViewById<Button>(R.id.btnFlowSettings).setOnClickListener {
            startActivity(Intent(this, FlowEditorActivity::class.java))
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
            if (phoneContacts.isEmpty()) {
                runOnUiThread {
                    Toast.makeText(this, "手机通讯录是空的", Toast.LENGTH_SHORT).show()
                }
                return@Thread
            }

            // 读取本地数据库已有的电话号码
            val dao = (application as App).database.contactDao()
            val existingPhones = dao.getAllSync().map {
                it.phone.replace("\\s+".toRegex(), "").replace("-", "")
            }.toSet()

            // 标记每个联系人是否已存在
            val isExisting = phoneContacts.map { (_, phone) ->
                phone.replace("\\s+".toRegex(), "").replace("-", "") in existingPhones
            }

            val names = phoneContacts.mapIndexed { i, (name, phone) ->
                if (isExisting[i]) "$name  $phone（已导入）" else "$name  $phone"
            }.toTypedArray()

            // 已存在的预勾选
            val checked = BooleanArray(names.size) { isExisting[it] }

            runOnUiThread {
                var allNewSelected = false

                val dialog = AlertDialog.Builder(this)
                    .setTitle("选择要导入的联系人")
                    .setMultiChoiceItems(names, checked) { dialogInterface, which, isChecked ->
                        // 已存在的不允许取消勾选
                        if (isExisting[which]) {
                            (dialogInterface as AlertDialog).listView.setItemChecked(which, true)
                            checked[which] = true
                        } else {
                            checked[which] = isChecked
                        }
                    }
                    .setPositiveButton("导入") { _, _ ->
                        // 只导入新勾选的（排除已存在的）
                        importSelected(phoneContacts, BooleanArray(checked.size) { i ->
                            checked[i] && !isExisting[i]
                        })
                    }
                    .setNegativeButton("取消", null)
                    .setNeutralButton("全选", null)
                    .create()

                dialog.show()

                // 已存在的项置灰
                val listView = dialog.listView
                listView.post {
                    for (i in names.indices) {
                        if (isExisting[i]) {
                            listView.getChildAt(i)?.alpha = 0.4f
                        }
                    }
                }

                // 全选只操作新联系人
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    allNewSelected = !allNewSelected
                    for (i in names.indices) {
                        if (!isExisting[i]) {
                            checked[i] = allNewSelected
                            listView.setItemChecked(i, allNewSelected)
                        }
                    }
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).text =
                        if (allNewSelected) "取消全选" else "全选"
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
