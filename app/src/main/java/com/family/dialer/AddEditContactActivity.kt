package com.family.dialer

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.family.dialer.data.Contact
import com.google.android.material.textfield.TextInputEditText

class AddEditContactActivity : AppCompatActivity() {

    private lateinit var tvTitle: TextView
    private lateinit var tvAvatarPreview: TextView
    private lateinit var etName: TextInputEditText
    private lateinit var etPhone: TextInputEditText
    private lateinit var etWechat: TextInputEditText
    private lateinit var colorContainer: LinearLayout
    private lateinit var btnSave: Button

    // 预设颜色
    private val presetColors by lazy {
        intArrayOf(
            ContextCompat.getColor(this, R.color.avatar_red),
            ContextCompat.getColor(this, R.color.avatar_pink),
            ContextCompat.getColor(this, R.color.avatar_purple),
            ContextCompat.getColor(this, R.color.avatar_blue),
            ContextCompat.getColor(this, R.color.avatar_teal),
            ContextCompat.getColor(this, R.color.avatar_green),
            ContextCompat.getColor(this, R.color.avatar_orange),
            ContextCompat.getColor(this, R.color.avatar_brown)
        )
    }

    private var selectedColor: Int = 0
    private var editContactId: Long = -1
    private var colorViews = mutableListOf<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_edit)

        tvTitle = findViewById(R.id.tvTitle)
        tvAvatarPreview = findViewById(R.id.tvAvatarPreview)
        etName = findViewById(R.id.etName)
        etPhone = findViewById(R.id.etPhone)
        etWechat = findViewById(R.id.etWechat)
        colorContainer = findViewById(R.id.colorContainer)
        btnSave = findViewById(R.id.btnSave)
        val btnBack: ImageButton = findViewById(R.id.btnBack)

        // 默认颜色
        selectedColor = presetColors[0]

        // 生成颜色选择圆圈
        setupColorPicker()

        // 实时更新头像预览
        etName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateAvatarPreview()
            }
        })

        // 检查是否是编辑模式
        editContactId = intent.getLongExtra("contact_id", -1)
        if (editContactId != -1L) {
            tvTitle.text = getString(R.string.title_edit)
            loadContact(editContactId)
        }

        // 返回
        btnBack.setOnClickListener { finish() }

        // 保存
        btnSave.setOnClickListener { saveContact() }

        // 初始化预览
        updateAvatarPreview()
    }

    private fun setupColorPicker() {
        val size = resources.getDimensionPixelSize(R.dimen.color_circle_size)
        val margin = resources.getDimensionPixelSize(R.dimen.spacing_small)

        presetColors.forEachIndexed { index, color ->
            val tv = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginStart = if (index == 0) 0 else margin
                }
                val bg = GradientDrawable()
                bg.shape = GradientDrawable.OVAL
                bg.setColor(color)
                background = bg
                gravity = android.view.Gravity.CENTER
                textSize = 18f
                setTextColor(Color.WHITE)
                text = ""
                setOnClickListener {
                    selectColor(index, color)
                }
            }
            colorViews.add(tv)
            colorContainer.addView(tv)
        }

        // 默认选中第一个
        selectColor(0, presetColors[0])
    }

    private fun selectColor(index: Int, color: Int) {
        selectedColor = color
        colorViews.forEach { it.text = "" }
        colorViews[index].text = "✓"
        updateAvatarPreview()
    }

    private fun updateAvatarPreview() {
        val name = etName.text?.toString()?.trim() ?: ""
        val initial = if (name.isNotEmpty()) name.first().toString() else "?"
        tvAvatarPreview.text = initial

        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(selectedColor)
        tvAvatarPreview.background = bg
    }

    private fun loadContact(id: Long) {
        Thread {
            val contact = (application as App).database.contactDao().getById(id)
            contact?.let {
                runOnUiThread {
                    etName.setText(it.name)
                    etPhone.setText(it.phone)
                    etWechat.setText(it.wechatName ?: "")
                    selectedColor = it.color
                    val colorIndex = presetColors.indexOfFirst { c -> c == it.color }
                    if (colorIndex >= 0) {
                        selectColor(colorIndex, it.color)
                    }
                    updateAvatarPreview()
                }
            }
        }.start()
    }

    private fun saveContact() {
        val name = etName.text?.toString()?.trim() ?: ""
        val phone = etPhone.text?.toString()?.trim() ?: ""
        val wechat = etWechat.text?.toString()?.trim()?.ifBlank { null }

        if (name.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_name_required), Toast.LENGTH_SHORT).show()
            return
        }
        if (phone.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_phone_required), Toast.LENGTH_SHORT).show()
            return
        }

        Thread {
            val dao = (application as App).database.contactDao()
            if (editContactId != -1L) {
                val existing = dao.getById(editContactId)
                existing?.let {
                    dao.update(it.copy(name = name, phone = phone, color = selectedColor, wechatName = wechat))
                }
            } else {
                dao.insert(Contact(name = name, phone = phone, color = selectedColor, wechatName = wechat))
            }
            runOnUiThread {
                Toast.makeText(this, getString(R.string.toast_saved), Toast.LENGTH_SHORT).show()
                val intent = Intent(this, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                startActivity(intent)
                finish()
            }
        }.start()
    }
}
