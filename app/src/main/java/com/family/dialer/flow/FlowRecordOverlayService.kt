package com.family.dialer.flow

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 浮窗坐标录制服务 —— 两阶段模式
 *
 * 阶段一：显示「开始录制」按钮（用户确认已到达目标页面后点击）
 * 阶段二：全屏透明触摸层捕获点击坐标
 */
class FlowRecordOverlayService : Service() {

    companion object {
        const val EXTRA_STEP_ID = "step_id"
        const val EXTRA_STEP_LABEL = "step_label"
    }

    private lateinit var windowManager: WindowManager
    private var confirmView: View? = null
    private var touchLayer: View? = null
    private var hintView: View? = null
    private var stepId: String = ""
    private var stepLabel: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stepId = intent?.getStringExtra(EXTRA_STEP_ID) ?: ""
        stepLabel = intent?.getStringExtra(EXTRA_STEP_LABEL) ?: ""

        if (stepId.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        removeAll()

        // ========== 阶段一：显示确认按钮 ==========
        showConfirmButton()

        return START_NOT_STICKY
    }

    /**
     * 阶段一：显示「开始录制」确认按钮
     */
    private fun showConfirmButton() {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val btn = TextView(this).apply {
            text = "✅ 前置步骤完成\n点击这里开始录制「$stepLabel」"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(48, 32, 48, 32)
            gravity = Gravity.CENTER

            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#DD1976D2"))
                cornerRadius = 24f
            }
            background = bg

            setOnClickListener {
                // 移除确认按钮，进入阶段二
                removeConfirmView()
                showRecordingLayer()
            }
        }

        // 长按取消
        btn.setOnLongClickListener {
            removeAll()
            stopSelf()
            true
        }

        confirmView = btn

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        windowManager.addView(confirmView, params)
    }

    /**
     * 阶段二：全屏透明触摸层 + 小型提示条
     */
    private fun showRecordingLayer() {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // 全屏半透明灰色触摸层（区分录制模式）
        val touchView = FrameLayout(this)
        touchView.setBackgroundColor(Color.parseColor("#4D000000"))  // 30% 黑色遮罩

        var downX = 0f
        var downY = 0f
        var isCancelled = false
        val moveThreshold = 15f  // 超过 15px 判定为滑动

        touchView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    isCancelled = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = Math.abs(event.rawX - downX)
                    val dy = Math.abs(event.rawY - downY)
                    if (dx > moveThreshold || dy > moveThreshold) {
                        isCancelled = true
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isCancelled) {
                        // 滑动操作 → 自动退出录制，不记录
                        removeAll()
                        stopSelf()
                    } else {
                        // 纯点击 → 记录坐标
                        val dm = resources.displayMetrics
                        val xPercent = downX / dm.widthPixels
                        val yPercent = downY / dm.heightPixels

                        FlowConfig.updateStepPosition(
                            this@FlowRecordOverlayService,
                            stepId, xPercent, yPercent
                        )

                        val resultIntent = Intent(FlowEditorActivity.ACTION_POSITION_RECORDED).apply {
                            setPackage(packageName)
                            putExtra(FlowEditorActivity.EXTRA_STEP_ID, stepId)
                            putExtra(FlowEditorActivity.EXTRA_X_PERCENT, xPercent)
                            putExtra(FlowEditorActivity.EXTRA_Y_PERCENT, yPercent)
                        }
                        sendBroadcast(resultIntent)

                        removeAll()
                        stopSelf()
                    }
                    true
                }
                else -> false
            }
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

        // 小型提示条（可拖动）
        val hint = TextView(this).apply {
            text = "📍 请点击「$stepLabel」的位置 | 长按取消"
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

        val hintParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 100
        }

        // 拖动
        var lastX = 0f
        var lastY = 0f
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

        hint.setOnLongClickListener {
            removeAll()
            stopSelf()
            true
        }

        hintView = hint
        windowManager.addView(hintView, hintParams)
    }

    private fun removeConfirmView() {
        confirmView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        confirmView = null
    }

    private fun removeAll() {
        removeConfirmView()
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
