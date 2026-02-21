package com.family.dialer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.family.dialer.flow.FlowConfig
import com.family.dialer.flow.FlowRecordOverlayService
import com.family.dialer.flow.FlowStep
import com.family.dialer.flow.StepType

/**
 * 微信视频拨打引擎 —— 统一流程驱动版
 *
 * 录制和执行使用完全相同的代码路径，通过 RunMode 区分：
 * - EXECUTE 模式：逐步自动执行每个步骤
 * - RECORD 模式：前置步骤与 EXECUTE 相同，到达录制目标步骤时启动坐标录制
 *
 * 新任务启动前会强制关闭旧任务。
 */
class WeChatVideoService : AccessibilityService() {

    /** 运行模式 */
    enum class RunMode { EXECUTE, RECORD }

    companion object {
        private const val TAG = "WeChatVideo"

        /** 要搜索的微信电话（由 ContactDetailActivity 设置） */
        var targetWechatName: String? = null

        /** 目标联系人手机号 */
        var targetPhone: String? = null

        /** 是否正在执行流程 */
        var isRunning = false

        /** 当前执行到的步骤索引 */
        var currentStepIndex = -1

        /** 由外部设置为 true 来触发流程启动 */
        var pendingStart = false

        /** 运行模式（EXECUTE / RECORD） */
        var runMode: RunMode = RunMode.EXECUTE

        /** RECORD 模式：要录制的目标步骤 ID */
        var recordTargetStepId: String? = null

        /** 服务实例引用 */
        private var instance: WeChatVideoService? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var retryCount = 0
    private val MAX_RETRY = 20
    private var flowSteps: List<FlowStep> = emptyList()

    private fun tip(msg: String) {
        Log.d(TAG, msg)
        handler.post {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onServiceConnected() {
        instance = this
        tip("电话铺：无障碍服务已启动")
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            packageNames = arrayOf("com.tencent.mm")
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 200
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        serviceInfo = info
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName?.toString() != "com.tencent.mm") return

        // 检查是否有待启动的流程
        if (pendingStart && !isRunning) {
            pendingStart = false
            startFlow()
            return
        }
    }

    /** 开始执行流程 */
    fun startFlow() {
        // 强制关闭旧任务
        finishFlow()

        flowSteps = FlowConfig.getFlow(this)

        // 复制搜索文字到剪贴板（PASTE 步骤用）
        val clipText = targetWechatName ?: targetPhone ?: ""
        if (clipText.isNotBlank()) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("search_text", clipText)
            clipboard.setPrimaryClip(clip)
            Log.d(TAG, "已复制到剪贴板: $clipText")
        }

        // 跳过第一步 LAUNCH（已由调用方处理）
        currentStepIndex = 1
        isRunning = true
        retryCount = 0
        Log.d(TAG, "流程开始 [${runMode.name}]，共 ${flowSteps.size} 步")
        // 等待微信启动后开始执行
        handler.postDelayed({ processCurrentStep() }, flowSteps[0].delayMs)
    }

    private fun processCurrentStep() {
        if (!isRunning || currentStepIndex < 0 || currentStepIndex >= flowSteps.size) {
            return
        }

        val step = flowSteps[currentStepIndex]
        val root = rootInActiveWindow

        Log.d(TAG, "执行步骤 ${currentStepIndex + 1}/${flowSteps.size}: ${step.label} (${step.type}) [${runMode.name}]")

        // RECORD 模式：到达目标步骤时启动坐标录制
        if (runMode == RunMode.RECORD && step.id == recordTargetStepId) {
            Log.d(TAG, "到达录制目标步骤: ${step.label}")
            launchRecordOverlay(step)
            return
        }

        when (step.type) {
            StepType.LAUNCH -> advanceToNextStep(step)
            StepType.TAP -> executeTapStep(step)
            StepType.PASTE -> executeTapStep(step)
            StepType.FIND_TAP -> {
                if (root == null) {
                    scheduleRetry("界面未就绪")
                    return
                }
                executeFindTapStep(step, root)
            }
            StepType.DELAY -> advanceToNextStep(step)
        }
    }

    /** RECORD 模式：启动坐标录制浮窗 */
    private fun launchRecordOverlay(step: FlowStep) {
        tip("到达录制步骤：${step.label}")
        val intent = Intent(this, FlowRecordOverlayService::class.java).apply {
            putExtra(FlowRecordOverlayService.EXTRA_STEP_ID, step.id)
            putExtra(FlowRecordOverlayService.EXTRA_STEP_LABEL, step.label)
        }
        startService(intent)
        finishFlow()
    }

    /** TAP 步骤：点击指定坐标 */
    private fun executeTapStep(step: FlowStep) {
        val xPercent = step.xPercent
        val yPercent = step.yPercent
        if (xPercent == null || yPercent == null) {
            tip("步骤「${step.label}」未设置坐标，请先录制")
            finishFlow()
            return
        }

        val dm = resources.displayMetrics
        val x = (xPercent * dm.widthPixels).toInt().toFloat()
        val y = (yPercent * dm.heightPixels).toInt().toFloat()

        tip("${step.label}")
        Log.d(TAG, "TAP ($x, $y) 百分比 ($xPercent, $yPercent)")

        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                advanceToNextStep(step)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                scheduleRetry("手势被取消")
            }
        }, null)
    }

    /** FIND_TAP 步骤：按文字查找节点并点击 */
    private fun executeFindTapStep(step: FlowStep, root: AccessibilityNodeInfo) {
        val searchText = if (step.id == "select_contact") {
            targetWechatName ?: ""
        } else {
            step.findText ?: ""
        }

        if (searchText.isBlank()) {
            tip("步骤「${step.label}」未设置查找文字")
            finishFlow()
            return
        }

        val nodes = root.findAccessibilityNodeInfosByText(searchText)
        if (nodes.isNullOrEmpty()) {
            scheduleRetry("找不到「$searchText」")
            return
        }

        val targetNode = if (step.id == "select_contact") {
            nodes.firstOrNull { node ->
                val nodeText = node.text?.toString() ?: ""
                nodeText == searchText && !isInsideEditText(node)
            } ?: nodes.firstOrNull { node ->
                !isInsideEditText(node) && isUnderContactSection(node, root)
            }
        } else {
            nodes.firstOrNull()
        }

        if (targetNode == null) {
            scheduleRetry("找不到可点击的「$searchText」")
            return
        }

        tip("${step.label}")
        val clickable = findClickableParent(targetNode) ?: targetNode
        clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        advanceToNextStep(step)
    }

    private fun isUnderContactSection(node: AccessibilityNodeInfo, root: AccessibilityNodeInfo): Boolean {
        val contactHeaders = root.findAccessibilityNodeInfosByText("联系人")
        if (contactHeaders.isNullOrEmpty()) return false
        val nodeRect = android.graphics.Rect()
        node.getBoundsInScreen(nodeRect)
        val contactRect = android.graphics.Rect()
        contactHeaders[0].getBoundsInScreen(contactRect)
        return nodeRect.top > contactRect.top
    }

    /** 推进到下一步 */
    private fun advanceToNextStep(currentStep: FlowStep) {
        retryCount = 0
        currentStepIndex++
        if (currentStepIndex >= flowSteps.size) {
            if (runMode == RunMode.EXECUTE) {
                tip("✅ 微信视频已发起")
                handler.postDelayed({ enableSpeaker() }, 3000)
            } else {
                tip("✅ 录制前置步骤完成")
            }
            finishFlow()
        } else {
            handler.postDelayed({ processCurrentStep() }, currentStep.delayMs)
        }
    }

    /** 结束流程 */
    private fun finishFlow() {
        handler.removeCallbacksAndMessages(null)
        isRunning = false
        currentStepIndex = -1
        retryCount = 0
    }

    private fun enableSpeaker() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            am.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = true
            tip("扬声器已开启")
        } catch (_: Exception) {}
    }

    // ---- 工具方法 ----

    private fun scheduleRetry(reason: String) {
        retryCount++
        if (retryCount > MAX_RETRY) {
            tip("操作失败：$reason（已重试${MAX_RETRY}次）")
            finishFlow()
        } else {
            if (retryCount % 5 == 0) {
                tip("重试中：$reason ($retryCount/$MAX_RETRY)")
            }
            handler.postDelayed({ processCurrentStep() }, 1000)
        }
    }

    private fun isInsideEditText(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 15) {
            if (current.className?.toString() == "android.widget.EditText") return true
            current = current.parent
            depth++
        }
        return false
    }

    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current = node.parent
        var depth = 0
        while (current != null && depth < 10) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
    }

    override fun onInterrupt() {
        finishFlow()
    }
}
