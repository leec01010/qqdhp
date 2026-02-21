package com.family.dialer.flow

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.family.dialer.R
import com.family.dialer.WeChatVideoService

class FlowEditorActivity : AppCompatActivity() {

    companion object {
        const val ACTION_POSITION_RECORDED = "com.family.dialer.POSITION_RECORDED"
        const val EXTRA_STEP_ID = "step_id"
        const val EXTRA_X_PERCENT = "x_percent"
        const val EXTRA_Y_PERCENT = "y_percent"
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: FlowStepAdapter
    private lateinit var etTestContact: EditText

    private val positionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val stepId = intent.getStringExtra(EXTRA_STEP_ID) ?: return
            val xPercent = intent.getFloatExtra(EXTRA_X_PERCENT, -1f)
            val yPercent = intent.getFloatExtra(EXTRA_Y_PERCENT, -1f)
            if (xPercent < 0 || yPercent < 0) return

            adapter.updatePosition(stepId, xPercent, yPercent)
            Toast.makeText(
                this@FlowEditorActivity,
                "✅ 已录制：X=${(xPercent * 100).toInt()}% Y=${(yPercent * 100).toInt()}%",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_flow_editor)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        etTestContact = findViewById(R.id.etTestContact)

        // 加载已保存的测试联系人
        etTestContact.setText(FlowConfig.getTestContact(this))

        recyclerView = findViewById(R.id.recyclerSteps)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = FlowStepAdapter { step -> startRecording(step) }
        recyclerView.adapter = adapter
        adapter.submitList(FlowConfig.getFlow(this))

        findViewById<Button>(R.id.btnReset).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("恢复默认")
                .setMessage("确定要恢复所有步骤为默认设置吗？")
                .setPositiveButton("确定") { _, _ ->
                    FlowConfig.resetToDefault(this)
                    adapter.submitList(FlowConfig.getFlow(this))
                    Toast.makeText(this, "已恢复默认", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            FlowConfig.saveFlow(this, adapter.getSteps())
            // 同时保存测试联系人
            FlowConfig.saveTestContact(this, etTestContact.text.toString().trim())
            Toast.makeText(this, "✅ 流程配置已保存", Toast.LENGTH_SHORT).show()
            finish()
        }

        val filter = IntentFilter(ACTION_POSITION_RECORDED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(positionReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(positionReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(positionReceiver)
    }

    /**
     * 启动坐标录制 —— 使用统一引擎（与真实执行完全相同的代码路径）
     *
     * 设置 WeChatVideoService.runMode = RECORD，指定录制目标步骤 ID，
     * 然后打开微信触发统一流程。前置步骤由 WeChatVideoService 统一处理，
     * 每一步都需要用户确认。到达目标步骤时自动进入坐标录制模式。
     */
    private fun startRecording(step: FlowStep) {
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("需要悬浮窗权限")
                .setMessage("录制坐标需要悬浮窗权限。\n\n请在设置中允许显示在其他应用上方。")
                .setPositiveButton("去设置") { _, _ ->
                    startActivity(Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    ))
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        if (!isAccessibilityEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("需要无障碍权限")
                .setMessage("录制坐标需要无障碍服务权限。\n\n请在设置中开启「亲情拨号助手」无障碍服务。")
                .setPositiveButton("去设置") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        val allSteps = adapter.getSteps()
        val stepIndex = allSteps.indexOfFirst { it.id == step.id }
        if (stepIndex < 0) return

        // 第一步（LAUNCH）不需要前置步骤，直接启动录制浮窗
        if (stepIndex == 0 || step.type == StepType.LAUNCH) {
            launchOverlayDirect(step)
            return
        }

        // 检查是否有 PASTE 前置步骤 → 需要测试联系人
        val needsTestContact = (0 until stepIndex).any { allSteps[it].type == StepType.PASTE }
        val testName = etTestContact.text.toString().trim()

        if (needsTestContact && testName.isEmpty()) {
            Toast.makeText(this, "请先填写「测试联系人」", Toast.LENGTH_LONG).show()
            etTestContact.requestFocus()
            return
        }

        // 保存当前编辑的流程（确保统一引擎使用最新配置）
        FlowConfig.saveFlow(this, allSteps)

        // 设置统一引擎为 RECORD 模式
        WeChatVideoService.runMode = WeChatVideoService.RunMode.RECORD
        WeChatVideoService.recordTargetStepId = step.id
        WeChatVideoService.targetPhone = testName.ifEmpty { null }
        WeChatVideoService.targetWechatName = null

        // 复制测试联系人到剪贴板
        if (testName.isNotEmpty()) {
            copyToClipboard(testName)
        }

        // 打开微信，触发统一流程
        launchWeChat()
        Toast.makeText(this, "正在打开微信，录制模式...", Toast.LENGTH_SHORT).show()

        // 延迟触发流程启动
        Handler(Looper.getMainLooper()).postDelayed({
            WeChatVideoService.pendingStart = true
        }, 500)
    }

    /** 复制文字到剪贴板 */
    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("contact_name", text)
        clipboard.setPrimaryClip(clip)
    }

    private fun launchWeChat() {
        val intent = packageManager.getLaunchIntentForPackage("com.tencent.mm") ?: return
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        startActivity(intent)
    }

    /** 直接启动录制浮窗（无前置步骤时使用） */
    private fun launchOverlayDirect(step: FlowStep) {
        val intent = Intent(this, FlowRecordOverlayService::class.java).apply {
            putExtra(FlowRecordOverlayService.EXTRA_STEP_ID, step.id)
            putExtra(FlowRecordOverlayService.EXTRA_STEP_LABEL, step.label)
        }
        startService(intent)
    }

    /** 检查无障碍服务是否已开启 */
    private fun isAccessibilityEnabled(): Boolean {
        val serviceName = "$packageName/${WeChatVideoService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(serviceName)
    }
}
