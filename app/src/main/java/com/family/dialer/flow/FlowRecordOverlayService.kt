package com.family.dialer.flow

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 浮窗坐标录制服务
 *
 * 改进版：
 * 1. 只在顶部显示一个小型悬浮提示条（不遮挡操作区域）
 * 2. 全屏透明触摸层捕获点击坐标
 * 3. 提示条可拖动，避免遮挡目标按钮
 */
class FlowRecordOverlayService : Service() {

    companion object {
        const val EXTRA_STEP_ID = "step_id"
        const val EXTRA_STEP_LABEL = "step_label"
    }

    private lateinit var windowManager: WindowManager
    private var touchLayer: View? = null
    private var hintView: View? = null
    private var stepId: String = ""
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stepId = intent?.getStringExtra(EXTRA_STEP_ID) ?: ""
        val stepLabel = intent?.getStringExtra(EXTRA_STEP_LABEL) ?: ""

        if (stepId.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        removeAll()

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // ========== 1. 全屏透明触摸层（捕获点击坐标） ==========
        val touchView = FrameLayout(this)
        // 完全透明，用户看不到
        touchView.setBackgroundColor(Color.TRANSPARENT)

        touchView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val rawX = event.rawX
                val rawY = event.rawY

                val dm = resources.displayMetrics
                val xPercent = rawX / dm.widthPixels
                val yPercent = rawY / dm.heightPixels

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

                removeAll()
                stopSelf()
                return@setOnTouchListener true
            }
            false
        }

        touchLayer = touchView

        val touchParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        windowManager.addView(touchLayer, touchParams)

        // ========== 2. 小型悬浮提示条（可拖动） ==========
        val hint = TextView(this).apply {
            text = "📍 请点击「$stepLabel」的位置"
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(32, 16, 32, 16)
            gravity = Gravity.CENTER

            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#CC333333"))
                cornerRadius = 40f
            }
            background = bg
        }

        // 支持拖动提示条
        var lastX = 0f
        var lastY = 0f
        val hintParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 100  // 距顶部约100px
        }

        hint.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX
                    lastY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - lastX).toInt()
                    val dy = (event.rawY - lastY).toInt()
                    hintParams.x += dx
                    hintParams.y += dy
                    lastX = event.rawX
                    lastY = event.rawY
                    windowManager.updateViewLayout(v, hintParams)
                    true
                }
                else -> false
            }
        }

        // 长按提示条取消录制
        hint.setOnLongClickListener {
            removeAll()
            stopSelf()
            true
        }

        hintView = hint
        windowManager.addView(hintView, hintParams)

        // 3秒后自动淡化提示条（降低存在感）
        handler.postDelayed({
            hintView?.alpha = 0.5f
        }, 3000)

        return START_NOT_STICKY
    }

    private fun removeAll() {
        touchLayer?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        touchLayer = null

        hintView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        hintView = null
    }

    override fun onDestroy() {
        super.onDestroy()
        removeAll()
    }
}
