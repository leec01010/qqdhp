package com.family.dialer.flow

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 浮窗坐标录制服务
 *
 * 在屏幕上覆盖一个半透明层，捕获用户的点击位置，
 * 将坐标（屏幕百分比）通过广播发回 FlowEditorActivity。
 */
class FlowRecordOverlayService : Service() {

    companion object {
        const val EXTRA_STEP_ID = "step_id"
        const val EXTRA_STEP_LABEL = "step_label"
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var stepId: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stepId = intent?.getStringExtra(EXTRA_STEP_ID) ?: ""
        val stepLabel = intent?.getStringExtra(EXTRA_STEP_LABEL) ?: ""

        if (stepId.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // 移除之前的浮窗（如果还在）
        removeOverlay()

        // 构建浮窗视图
        val rootLayout = FrameLayout(this).apply {
            // 半透明背景
            setBackgroundColor(Color.parseColor("#55000000"))
        }

        // 顶部提示栏
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#DD333333"))
            setPadding(48, 48, 48, 48)
            gravity = Gravity.CENTER
        }

        val tvTitle = TextView(this).apply {
            text = "📍 坐标录制模式"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        val tvInstruction = TextView(this).apply {
            text = "请点击「$stepLabel」的位置\n点击屏幕任意位置即可录制坐标"
            textSize = 14f
            setTextColor(Color.parseColor("#CCCCCC"))
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 0)
        }

        val tvCancel = TextView(this).apply {
            text = "[ 点击这里取消 ]"
            textSize = 14f
            setTextColor(Color.parseColor("#FF6B6B"))
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
            setOnClickListener {
                removeOverlay()
                stopSelf()
            }
        }

        topBar.addView(tvTitle)
        topBar.addView(tvInstruction)
        topBar.addView(tvCancel)

        val topBarParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP
        }
        rootLayout.addView(topBar, topBarParams)

        // 点击捕获区域（排除顶部栏）
        rootLayout.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val rawX = event.rawX
                val rawY = event.rawY

                // 获取屏幕尺寸
                val dm = resources.displayMetrics
                val screenW = dm.widthPixels
                val screenH = dm.heightPixels

                val xPercent = rawX / screenW
                val yPercent = rawY / screenH

                // 忽略顶部栏区域的点击（前 15%）
                if (yPercent < 0.15f) {
                    return@setOnTouchListener false
                }

                // 保存到 FlowConfig
                FlowConfig.updateStepPosition(
                    this@FlowRecordOverlayService,
                    stepId, xPercent, yPercent
                )

                // 发送广播通知 FlowEditorActivity
                val resultIntent = Intent(FlowEditorActivity.ACTION_POSITION_RECORDED).apply {
                    setPackage(packageName)
                    putExtra(FlowEditorActivity.EXTRA_STEP_ID, stepId)
                    putExtra(FlowEditorActivity.EXTRA_X_PERCENT, xPercent)
                    putExtra(FlowEditorActivity.EXTRA_Y_PERCENT, yPercent)
                }
                sendBroadcast(resultIntent)

                // 移除浮窗并停止服务
                removeOverlay()
                stopSelf()
                return@setOnTouchListener true
            }
            false
        }

        overlayView = rootLayout

        // 浮窗参数
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        windowManager.addView(overlayView, params)

        return START_NOT_STICKY
    }

    private fun removeOverlay() {
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        overlayView = null
    }

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
    }
}
