package com.family.dialer.flow

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.family.dialer.R

class FlowEditorActivity : AppCompatActivity() {

    companion object {
        const val ACTION_POSITION_RECORDED = "com.family.dialer.POSITION_RECORDED"
        const val EXTRA_STEP_ID = "step_id"
        const val EXTRA_X_PERCENT = "x_percent"
        const val EXTRA_Y_PERCENT = "y_percent"
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: FlowStepAdapter

    /** 当前正在录制的步骤 ID */
    private var recordingStepId: String? = null

    /** 接收浮窗录制结果的广播 */
    private val positionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val stepId = intent.getStringExtra(EXTRA_STEP_ID) ?: return
            val xPercent = intent.getFloatExtra(EXTRA_X_PERCENT, -1f)
            val yPercent = intent.getFloatExtra(EXTRA_Y_PERCENT, -1f)
            if (xPercent < 0 || yPercent < 0) return

            adapter.updatePosition(stepId, xPercent, yPercent)
            Toast.makeText(
                this@FlowEditorActivity,
                "✅ 已录制坐标：X=${(xPercent * 100).toInt()}% Y=${(yPercent * 100).toInt()}%",
                Toast.LENGTH_SHORT
            ).show()
            recordingStepId = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_flow_editor)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        recyclerView = findViewById(R.id.recyclerSteps)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = FlowStepAdapter { step ->
            startRecording(step)
        }
        recyclerView.adapter = adapter

        // 加载流程
        adapter.submitList(FlowConfig.getFlow(this))

        // 恢复默认
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

        // 保存
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            FlowConfig.saveFlow(this, adapter.getSteps())
            Toast.makeText(this, "✅ 流程配置已保存", Toast.LENGTH_SHORT).show()
            finish()
        }

        // 注册广播接收器
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
        // 检查悬浮窗权限
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("需要悬浮窗权限")
                .setMessage("录制坐标需要悬浮窗权限，以便在微信界面上方显示指引。\n\n请在设置中允许「电话铺」显示在其他应用上方。")
                .setPositiveButton("去设置") { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        recordingStepId = step.id

        // 启动浮窗录制服务
        val intent = Intent(this, FlowRecordOverlayService::class.java).apply {
            putExtra(FlowRecordOverlayService.EXTRA_STEP_ID, step.id)
            putExtra(FlowRecordOverlayService.EXTRA_STEP_LABEL, step.label)
        }
        startService(intent)

        Toast.makeText(this, "请切换到微信，点击「${step.label}」的位置", Toast.LENGTH_LONG).show()
    }
}
