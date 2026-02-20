package com.family.dialer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
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
 * - PASTE 步骤：从剪贴板粘贴联系人名（先复制到剪贴板再 ACTION_PASTE）
 * - FIND_TAP 步骤：按文字查找节点并点击
 * - LAUNCH 步骤：启动 App（由调用方处理）
 * - DELAY 步骤：纯等待
 */
class WeChatVideoService : AccessibilityService() {

    companion object {
        private const val TAG = "WeChatVideo"

        /** 要搜索的微信备注名（由 ContactDetailActivity 设置） */
        var targetWechatName: String? = null

        /** 目标联系人手机号（用于添加朋友→手机号搜索） */
        var targetPhone: String? = null

        /** 是否正在执行流程 */
        var isRunning = false

        /** 当前执行到的步骤索引 */
        var currentStepIndex = -1

        /** 由 ContactDetailActivity 设置为 true 来触发流程启动 */
        var pendingStart = false

        /** 服务实例引用（用于录制时执行前置步骤） */
        private var instance: WeChatVideoService? = null

    /** 流程执行时的灰色遮罩 */
    private var overlayView: View? = null

        /**
         * 执行单次坐标点击（供 FlowEditorActivity 录制前置步骤使用）
         */
        fun executeSingleTap(x: Float, y: Float) {
            val svc = instance ?: return
            val path = android.graphics.Path().apply { moveTo(x, y) }
            val gesture = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 100))
                .build()
            svc.dispatchGesture(gesture, null, null)
            android.util.Log.d(TAG, "录制前置 TAP: ($x, $y)")
        }

        // PASTE 步骤现在等同于 TAP（用户录制键盘粘贴建议的坐标）
        // 剪贴板复制在 startFlow() / FlowEditorActivity 中完成

        /**
         * 通过文字查找节点并点击（录制前置 FIND_TAP 步骤）
         */
        fun executeTestFindTap(text: String) {
            val svc = instance ?: return
            val root = svc.rootInActiveWindow ?: return
            val nodes = root.findAccessibilityNodeInfosByText(text)
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    if (node.isClickable) {
                        node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                        android.util.Log.d(TAG, "录制前置 FIND_TAP 点击: $text")
                        return
                    }
                    // 往上找可点击的父节点
                    var parent = node.parent
                    while (parent != null) {
                        if (parent.isClickable) {
                            parent.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                            android.util.Log.d(TAG, "录制前置 FIND_TAP 点击父节点: $text")
                            return
                        }
                        parent = parent.parent
                    }
                }
            }
        }

        private fun findEditText(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
            if (node.className?.toString() == "android.widget.EditText") return node
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val result = findEditText(child)
                if (result != null) return result
            }
            return null
        }
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

        if (!isRunning) return
        processCurrentStep()
    }

    /** 开始执行流程（由外部调用） */
    fun startFlow() {
        flowSteps = FlowConfig.getFlow(this)

        // 复制手机号到剪贴板（PASTE 步骤用）
        val clipText = targetPhone ?: targetWechatName ?: ""
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
        showOverlay()
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
            StepType.PASTE -> {
                // PASTE = 自动复制到剪贴板 + 用户录制的坐标点击（键盘粘贴建议）
                executeTapStep(step)
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
     * FIND_TAP 步骤：按文字查找节点并点击
     * 对于 select_contact 步骤，使用精确匹配（避免「最常使用」冒充）
     */
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
            if (retryCount == 5) {
                val texts = collectTexts(root)
                tip("找不到「$searchText」，界面: $texts")
            }
            scheduleRetry("找不到「$searchText」")
            return
        }

        val targetNode = if (step.id == "select_contact") {
            // 精确匹配：只匹配 text 完全等于 searchText 的节点
            // 并排除 EditText 内的匹配和「最常使用」区域的匹配
            nodes.firstOrNull { node ->
                val nodeText = node.text?.toString() ?: ""
                nodeText == searchText && !isInsideEditText(node)
            } ?: nodes.firstOrNull { node ->
                // 如果找不到完全匹配，在「联系人」分组下找含有 searchText 的
                !isInsideEditText(node) && isUnderContactSection(node, root)
            }
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

    /**
     * 检查节点是否在「联系人」分组下（而不是「最常使用」分组）
     * 通过检查同层级是否有「联系人」标题来判断
     */
    private fun isUnderContactSection(node: AccessibilityNodeInfo, root: AccessibilityNodeInfo): Boolean {
        // 查找「联系人」和「最常使用」标题节点
        val contactHeaders = root.findAccessibilityNodeInfosByText("联系人")
        val frequentHeaders = root.findAccessibilityNodeInfosByText("最常使用")

        if (contactHeaders.isNullOrEmpty()) return false

        // 获取节点的 Y 坐标
        val nodeRect = android.graphics.Rect()
        node.getBoundsInScreen(nodeRect)

        val contactRect = android.graphics.Rect()
        contactHeaders[0].getBoundsInScreen(contactRect)

        // 如果有「最常使用」标题，节点应该在「联系人」标题之下
        // 即节点的 Y 坐标大于「联系人」标题的 Y 坐标
        return nodeRect.top > contactRect.top
    }

    /** 推进到下一步 */
    private fun advanceToNextStep(currentStep: FlowStep) {
        retryCount = 0
        currentStepIndex++
        if (currentStepIndex >= flowSteps.size) {
            // 全部步骤完成
            tip("✅ 流程执行完毕")
            // 延迟提示：如果未成功可能是非好友
            handler.postDelayed({
                tip("如未发起通话，请检查该联系人是否是您的微信好友")
            }, 5000)
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
        removeOverlay()
        retryCount = 0
    }

    /** 显示流程执行灰色遮罩（不拦截触摸） */
    private fun showOverlay() {
        if (overlayView != null) return
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
        }

        val view = View(this)
        view.setBackgroundColor(Color.parseColor("#4D000000"))  // 30% 黑色遮罩

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        wm.addView(view, params)
        overlayView = view
        Log.d(TAG, "流程遮罩已显示")
    }

    /** 移除流程执行灰色遮罩 */
    private fun removeOverlay() {
        overlayView?.let {
            try {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                wm.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "移除遮罩失败: ${e.message}")
            }
            overlayView = null
            Log.d(TAG, "流程遮罩已移除")
        }
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
