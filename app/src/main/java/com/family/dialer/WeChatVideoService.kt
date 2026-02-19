package com.family.dialer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.family.dialer.flow.FlowConfig
import com.family.dialer.flow.FlowStep
import com.family.dialer.flow.StepType

/**
 * 微信视频拨打引擎 —— 流程驱动版
 *
 * 根据 FlowConfig 中保存的流程（用户可自定义坐标）逐步执行：
 * - TAP 步骤：使用 dispatchGesture() 在指定百分比坐标处点击
 * - INPUT 步骤：使用 AccessibilityNodeInfo 的 ACTION_SET_TEXT
 * - FIND_TAP 步骤：按文字查找节点并点击
 * - LAUNCH 步骤：启动 App（由调用方处理）
 * - DELAY 步骤：纯等待
 */
class WeChatVideoService : AccessibilityService() {

    companion object {
        private const val TAG = "WeChatVideo"

        /** 要搜索的微信备注名（由 ContactDetailActivity 设置） */
        var targetWechatName: String? = null

        /** 是否正在执行流程 */
        var isRunning = false

        /** 当前执行到的步骤索引 */
        var currentStepIndex = -1

        /** 由 ContactDetailActivity 设置为 true 来触发流程启动 */
        var pendingStart = false
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName?.toString() != "com.tencent.mm") return

        // 检查是否有待启动的流程
        if (pendingStart && !isRunning) {
            pendingStart = false
            startFlow()
            return
        }

        if (!isRunning) return
        processCurrentStep()
    }

    /** 开始执行流程（由外部调用） */
    fun startFlow() {
        flowSteps = FlowConfig.getFlow(this)
        // 跳过第一步 LAUNCH（已由调用方处理）
        currentStepIndex = 1
        isRunning = true
        retryCount = 0
        Log.d(TAG, "流程开始，共 ${flowSteps.size} 步")
        handler.postDelayed({ processCurrentStep() }, flowSteps[0].delayMs)
    }

    private fun processCurrentStep() {
        if (!isRunning || currentStepIndex < 0 || currentStepIndex >= flowSteps.size) {
            return
        }

        val step = flowSteps[currentStepIndex]
        val root = rootInActiveWindow

        Log.d(TAG, "执行步骤 ${currentStepIndex + 1}/${flowSteps.size}: ${step.label} (${step.type})")

        when (step.type) {
            StepType.LAUNCH -> {
                // LAUNCH 由调用方处理，直接跳过
                advanceToNextStep(step)
            }
            StepType.TAP -> {
                executeTapStep(step)
            }
            StepType.INPUT -> {
                if (root == null) {
                    scheduleRetry("界面未就绪")
                    return
                }
                executeInputStep(step, root)
            }
            StepType.FIND_TAP -> {
                if (root == null) {
                    scheduleRetry("界面未就绪")
                    return
                }
                executeFindTapStep(step, root)
            }
            StepType.DELAY -> {
                advanceToNextStep(step)
            }
        }
    }

    /**
     * TAP 步骤：使用 dispatchGesture() 在指定百分比坐标处点击
     */
    private fun executeTapStep(step: FlowStep) {
        val xPercent = step.xPercent
        val yPercent = step.yPercent
        if (xPercent == null || yPercent == null) {
            tip("步骤「${step.label}」未设置坐标，请先在流程设置中录制")
            finishFlow()
            return
        }

        val dm = resources.displayMetrics
        val x = (xPercent * dm.widthPixels).toInt().toFloat()
        val y = (yPercent * dm.heightPixels).toInt().toFloat()

        tip("步骤${currentStepIndex + 1}/${flowSteps.size}：${step.label}")
        Log.d(TAG, "TAP 点击坐标: ($x, $y) 百分比: ($xPercent, $yPercent)")

        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "TAP 点击完成")
                advanceToNextStep(step)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "TAP 点击被取消")
                scheduleRetry("手势被取消")
            }
        }, null)
    }

    /**
     * INPUT 步骤：找到 EditText 并输入联系人备注名
     */
    private fun executeInputStep(step: FlowStep, root: AccessibilityNodeInfo) {
        val targetName = targetWechatName
        if (targetName.isNullOrBlank()) {
            tip("未设置目标联系人")
            finishFlow()
            return
        }

        val editText = findNodeByClassName(root, "android.widget.EditText")
        if (editText != null) {
            editText.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            editText.performAction(AccessibilityNodeInfo.ACTION_CLICK)

            tip("步骤${currentStepIndex + 1}/${flowSteps.size}：输入「$targetName」")
            val args = Bundle()
            args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                targetName
            )
            editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            advanceToNextStep(step)
        } else {
            scheduleRetry("搜索框还没出现")
        }
    }

    /**
     * FIND_TAP 步骤：按文字查找节点并点击
     * 对于 select_contact 步骤，使用 targetWechatName 作为查找文字
     */
    private fun executeFindTapStep(step: FlowStep, root: AccessibilityNodeInfo) {
        // 确定查找文字
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
            if (retryCount == 5) {
                val texts = collectTexts(root)
                tip("找不到「$searchText」，界面: $texts")
            }
            scheduleRetry("找不到「$searchText」")
            return
        }

        // 对于 select_contact，排除 EditText 中的匹配
        val targetNode = if (step.id == "select_contact") {
            nodes.firstOrNull { !isInsideEditText(it) }
        } else {
            nodes.firstOrNull()
        }

        if (targetNode == null) {
            scheduleRetry("找不到可点击的「$searchText」")
            return
        }

        tip("步骤${currentStepIndex + 1}/${flowSteps.size}：${step.label}")
        val clickable = findClickableParent(targetNode) ?: targetNode
        clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        advanceToNextStep(step)
    }

    /** 推进到下一步 */
    private fun advanceToNextStep(currentStep: FlowStep) {
        retryCount = 0
        currentStepIndex++
        if (currentStepIndex >= flowSteps.size) {
            // 全部步骤完成
            tip("🎯 视频通话发起成功！")
            handler.postDelayed({ enableSpeaker() }, 3000)
            finishFlow()
        } else {
            handler.postDelayed({ processCurrentStep() }, currentStep.delayMs)
        }
    }

    /** 结束流程 */
    private fun finishFlow() {
        isRunning = false
        currentStepIndex = -1
        retryCount = 0
    }

    private fun enableSpeaker() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_IN_COMMUNICATION
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

    /** 检查节点是否在 EditText 内部 */
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

    private fun findNodeByClassName(root: AccessibilityNodeInfo, className: String): AccessibilityNodeInfo? {
        return traverseFind(root) { it.className?.toString() == className }
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

    private fun collectTexts(root: AccessibilityNodeInfo): String {
        val texts = mutableListOf<String>()
        traverseAll(root) { node ->
            node.text?.toString()?.let {
                if (it.isNotBlank() && it.length < 20) texts.add(it)
            }
        }
        return texts.take(8).joinToString(", ")
    }

    private fun traverseFind(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = traverseFind(child, predicate)
            if (result != null) return result
        }
        return null
    }

    private fun traverseAll(node: AccessibilityNodeInfo, action: (AccessibilityNodeInfo) -> Unit) {
        action(node)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { traverseAll(it, action) }
        }
    }

    override fun onInterrupt() {
        finishFlow()
    }
}
