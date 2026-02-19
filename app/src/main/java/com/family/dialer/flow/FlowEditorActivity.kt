package com.family.dialer.flow

import android.content.BroadcastReceiver
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
                .setMessage("录制坐标需要悬浮窗权限。\n\n请在设置中允许「电话铺」显示在其他应用上方。")
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

        // 检查前面是否有 INPUT 步骤，如果有则需要测试联系人
        val needsTestContact = (0 until stepIndex).any { allSteps[it].type == StepType.INPUT }
        val testName = etTestContact.text.toString().trim()

        if (needsTestContact && testName.isEmpty()) {
            Toast.makeText(this, "请先填写「测试联系人」，用于自动执行前面的搜索步骤", Toast.LENGTH_LONG).show()
            etTestContact.requestFocus()
            return
        }

        recordingStepId = step.id

        // 收集前置步骤（index 1 到 targetIndex-1，跳过 LAUNCH 和 DELAY）
        val preSteps = mutableListOf<FlowStep>()
        for (i in 1 until stepIndex) {
            val s = allSteps[i]
            if (s.type != StepType.LAUNCH && s.type != StepType.DELAY) {
                preSteps.add(s)
            }
        }

        if (preSteps.isEmpty()) {
            // 没有前置步骤（比如第一个 TAP），直接打开微信后启动浮窗
            val hasLaunch = (0 until stepIndex).any { allSteps[it].type == StepType.LAUNCH }
            if (hasLaunch) {
                launchWeChat()
                handler.postDelayed({ launchOverlay(step) }, allSteps[0].delayMs)
            } else {
                launchOverlay(step)
            }
            return
        }

        // 有前置步骤：先打开微信，等待后逐步执行
        launchWeChat()
        Toast.makeText(this, "正在自动执行前置步骤...", Toast.LENGTH_SHORT).show()

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
            // 前置步骤全部执行完毕，启动浮窗录制
            handler.postDelayed({ launchOverlay(targetStep) }, 1500)
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
            StepType.INPUT -> {
                WeChatVideoService.executeTestInput(testName)
            }
            StepType.FIND_TAP -> {
                val text = if (step.findText.isNullOrEmpty()) testName else step.findText
                WeChatVideoService.executeTestFindTap(text)
            }
            else -> { /* skip */ }
        }

        // 等待当前步骤完成后执行下一步
        handler.postDelayed({
            runPreStepChain(preSteps, index + 1, targetStep, testName)
        }, step.delayMs)
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
