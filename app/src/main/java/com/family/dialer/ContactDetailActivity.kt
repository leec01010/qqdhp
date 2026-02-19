package com.family.dialer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Html
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.family.dialer.data.Contact


class ContactDetailActivity : AppCompatActivity() {

    private lateinit var tvAvatar: TextView
    private lateinit var tvName: TextView
    private lateinit var tvPhone: TextView
    private lateinit var tvWechat: TextView
    private lateinit var btnCall: Button
    private lateinit var btnWechatVideo: Button

    private var contact: Contact? = null
    private val CALL_PERMISSION_CODE = 200

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contact_detail)

        tvAvatar = findViewById(R.id.tvAvatar)
        tvName = findViewById(R.id.tvName)
        tvPhone = findViewById(R.id.tvPhone)
        tvWechat = findViewById(R.id.tvWechat)
        btnCall = findViewById(R.id.btnCall)
        btnWechatVideo = findViewById(R.id.btnWechatVideo)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        val contactId = intent.getLongExtra("contact_id", -1)
        if (contactId == -1L) {
            finish()
            return
        }

        loadContact(contactId)

        btnCall.setOnClickListener {
            contact?.let { showCallConfirmDialog(it) }
        }

        btnWechatVideo.setOnClickListener {
            contact?.let { startWeChatVideo(it) }
        }
    }

    private fun loadContact(id: Long) {
        Thread {
            val c = (application as App).database.contactDao().getById(id)
            runOnUiThread {
                if (c == null) {
                    finish()
                    return@runOnUiThread
                }
                contact = c
                bindContact(c)
            }
        }.start()
    }

    private fun bindContact(c: Contact) {
        // 头像
        val initial = if (c.name.isNotEmpty()) c.name.first().toString() else "?"
        tvAvatar.text = initial
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(c.color)
        tvAvatar.background = bg

        // 基本信息
        tvName.text = c.name
        tvPhone.text = c.phone

        // 微信备注名
        if (!c.wechatName.isNullOrBlank()) {
            tvWechat.text = "微信: ${c.wechatName}"
            tvWechat.visibility = View.VISIBLE
            btnWechatVideo.isEnabled = true
            btnWechatVideo.alpha = 1f
        } else {
            tvWechat.visibility = View.GONE
            btnWechatVideo.isEnabled = false
            btnWechatVideo.alpha = 0.4f
        }
    }

    // ---- 打电话 ----

    private fun showCallConfirmDialog(c: Contact) {
        val msg = Html.fromHtml(
            "确定要拨打吗？<br/><br/>" +
            "名字<br/><b><font color='#C62828'>${c.name}</font></b><br/><br/>" +
            "电话<br/><b><font color='#C62828'>${c.phone}</font></b>",
            Html.FROM_HTML_MODE_COMPACT
        )
        AlertDialog.Builder(this)
            .setTitle("拨打电话")
            .setMessage(msg)
            .setPositiveButton("拨打") { _, _ -> makeCall(c) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun makeCall(c: Contact) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            == PackageManager.PERMISSION_GRANTED) {
            dialPhone(c)
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CALL_PHONE), CALL_PERMISSION_CODE
            )
        }
    }

    private fun dialPhone(c: Contact) {
        val intent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:${c.phone}")
        }
        try {
            startActivity(intent)
            // 延迟 2 秒后开启扬声器（等待通话建立）
            Handler(Looper.getMainLooper()).postDelayed({
                enableSpeaker()
            }, 2000)
        } catch (e: SecurityException) {
            Toast.makeText(this, "需要电话权限才能拨号", Toast.LENGTH_SHORT).show()
        }
    }

    private fun enableSpeaker() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_CALL
        audioManager.isSpeakerphoneOn = true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CALL_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                contact?.let { dialPhone(it) }
            } else {
                Toast.makeText(this, "需要电话权限才能拨号", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---- 微信视频 ----

    private fun startWeChatVideo(c: Contact) {
        val wechatName = c.wechatName
        if (wechatName.isNullOrBlank()) {
            Toast.makeText(this, "请先设置该联系人的微信备注名", Toast.LENGTH_SHORT).show()
            return
        }

        // 检查无障碍服务是否开启
        if (!isAccessibilityServiceEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("需要开启无障碍权限")
                .setMessage("首次使用微信视频功能，需要开启「电话铺」的无障碍权限。\n\n请在设置中找到「电话铺」并开启。")
                .setPositiveButton("去设置") { _, _ ->
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    startActivity(intent)
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        // 检查微信是否安装
        if (!isWeChatInstalled()) {
            Toast.makeText(this, "未检测到微信", Toast.LENGTH_SHORT).show()
            return
        }

        // 设置目标并启动微信
        WeChatVideoService.targetWechatName = wechatName

        val launchIntent = packageManager.getLaunchIntentForPackage("com.tencent.mm")
        launchIntent?.let {
            it.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            startActivity(it)
            Toast.makeText(this, "正在打开微信，自动搜索「$wechatName」...", Toast.LENGTH_LONG).show()

            // 延迟启动流程引擎（等待微信打开）
            Handler(Looper.getMainLooper()).postDelayed({
                // 通过静态方式触发服务开始流程
                WeChatVideoService.pendingStart = true
            }, 500)
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "$packageName/${WeChatVideoService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(service)
    }

    private fun isWeChatInstalled(): Boolean {
        return try {
            packageManager.getPackageInfo("com.tencent.mm", 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
}
