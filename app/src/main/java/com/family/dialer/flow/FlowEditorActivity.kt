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
    private val handler = Handler(Looper.getMainLooper())

    private var recordingStepId: String? = null

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
            recordingStepId = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_flow_editor)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        etTestContact = findViewById(R.id.etTestContact)

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

    /** 启动坐标录制 */
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

        val allSteps = adapter.getSteps()
        val stepIndex = allSteps.indexOfFirst { it.id == step.id }
        if (stepIndex < 0) return

        // 检查是否有 PASTE 前置步骤 → 需要测试联系人
        val needsTestContact = (0 until stepIndex).any { allSteps[it].type == StepType.PASTE }
        val testName = etTestContact.text.toString().trim()

        if (needsTestContact && testName.isEmpty()) {
            Toast.makeText(this, "请先填写「测试联系人」", Toast.LENGTH_LONG).show()
            etTestContact.requestFocus()
            return
        }

        recordingStepId = step.id

        // 如果有前置步骤需要执行
        val hasLaunchBefore = (0 until stepIndex).any { allSteps[it].type == StepType.LAUNCH }

        if (!hasLaunchBefore || stepIndex == 0) {
            // 没有前置步骤（或者是第一步），直接启动浮窗
            launchOverlay(step)
            return
        }

        // 先复制测试联系人到剪贴板（如果需要）
        if (testName.isNotEmpty()) {
            copyToClipboard(testName)
        }

        // 打开微信
        launchWeChat()
        Toast.makeText(this, "正在自动执行前置步骤...", Toast.LENGTH_SHORT).show()

        // 收集前置步骤（跳过 LAUNCH）
        val preSteps = mutableListOf<FlowStep>()
        for (i in 1 until stepIndex) {
            preSteps.add(allSteps[i])
        }

        // 等微信启动后执行
        handler.postDelayed({
            runPreStepChain(preSteps, 0, step, testName)
        }, allSteps[0].delayMs)
    }

    /**
     * 逐步执行前置步骤链
     */
    private fun runPreStepChain(
        preSteps: List<FlowStep>,
        index: Int,
        targetStep: FlowStep,
        testName: String
    ) {
        if (index >= preSteps.size) {
            // 前置步骤全部完成 → 启动浮窗（阶段一：确认按钮）
            handler.postDelayed({ launchOverlay(targetStep) }, 1000)
            return
        }

        val step = preSteps[index]
        val dm = resources.displayMetrics

        when (step.type) {
            StepType.TAP -> {
                if (step.xPercent != null && step.yPercent != null) {
                    val x = (step.xPercent * dm.widthPixels).toFloat()
                    val y = (step.yPercent * dm.heightPixels).toFloat()
                    WeChatVideoService.executeSingleTap(x, y)
                }
            }
            StepType.PASTE -> {
                // 粘贴：通过无障碍服务执行粘贴操作
                WeChatVideoService.executePaste()
            }
            StepType.FIND_TAP -> {
                val text = if (step.findText.isNullOrEmpty()) testName else step.findText
                WeChatVideoService.executeTestFindTap(text)
            }
            else -> { /* LAUNCH, DELAY: skip */ }
        }

        handler.postDelayed({
            runPreStepChain(preSteps, index + 1, targetStep, testName)
        }, step.delayMs)
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

    private fun launchOverlay(step: FlowStep) {
        val intent = Intent(this, FlowRecordOverlayService::class.java).apply {
            putExtra(FlowRecordOverlayService.EXTRA_STEP_ID, step.id)
            putExtra(FlowRecordOverlayService.EXTRA_STEP_LABEL, step.label)
        }
        startService(intent)
    }
}
