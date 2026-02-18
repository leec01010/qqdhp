package com.family.dialer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Rect
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

class WeChatVideoService : AccessibilityService() {

    enum class Step {
        IDLE, OPEN_WECHAT, INPUT_NAME, CLICK_RESULT, CLICK_PLUS, CLICK_VIDEO_CALL, DONE
    }

    companion object {
        var targetWechatName: String? = null
        var currentStep = Step.IDLE
    }

    private val handler = Handler(Looper.getMainLooper())
    private var retryCount = 0
    private val MAX_RETRY = 20

    private fun tip(msg: String) {
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
        if (event == null || currentStep == Step.IDLE || currentStep == Step.DONE) return
        if (event.packageName?.toString() != "com.tencent.mm") return
        processCurrentStep()
    }

    private fun processCurrentStep() {
        val root = rootInActiveWindow
        if (root == null) {
            scheduleRetry("界面未就绪")
            return
        }

        when (currentStep) {
            Step.OPEN_WECHAT -> handleOpenWechat(root)
            Step.INPUT_NAME -> handleInputName(root)
            Step.CLICK_RESULT -> handleClickResult(root)
            Step.CLICK_PLUS -> handleClickPlus(root)
            Step.CLICK_VIDEO_CALL -> handleClickVideoCall(root)
            Step.IDLE, Step.DONE -> {}
        }
    }

    /**
     * 步骤1：点击微信顶栏的放大镜搜索按钮
     *
     * 微信主界面顶栏布局：左边 "微信(80)"，右边 🔍 ⊕
     * 搜索 🔍 在 ⊕ 的左边，都在顶栏右侧
     * 这些按钮没有 contentDescription，只能按位置找
     */
    private fun handleOpenWechat(root: AccessibilityNodeInfo) {
        // 如果已经有 EditText（搜索框已出现），直接跳到输入
        val editText = findNodeByClassName(root, "android.widget.EditText")
        if (editText != null) {
            currentStep = Step.INPUT_NAME
            retryCount = 0
            processCurrentStep()
            return
        }

        // 收集顶栏区域（y < 200px）的所有可点击元素，按 x 坐标排序
        val topClickables = mutableListOf<Pair<AccessibilityNodeInfo, Rect>>()
        traverseAll(root) { node ->
            if (node.isClickable) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                // 顶栏区域：y < 250，且不能太小（排除状态栏图标）
                if (rect.top < 250 && rect.height() > 20 && rect.width() > 20) {
                    topClickables.add(Pair(node, rect))
                }
            }
        }

        // 按 x 坐标从左到右排序
        topClickables.sortBy { it.second.left }

        if (topClickables.size >= 2) {
            // 右边倒数第二个就是 🔍（最右边是 ⊕）
            val searchBtn = topClickables[topClickables.size - 2].first
            val rect = topClickables[topClickables.size - 2].second
            tip("步骤1/5：点击搜索🔍 (位置:${rect.left},${rect.top})")
            searchBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            currentStep = Step.INPUT_NAME
            retryCount = 0
            handler.postDelayed({ processCurrentStep() }, 1000)
        } else if (topClickables.size == 1) {
            // 只有一个，试试点击
            tip("步骤1/5：点击顶栏按钮")
            topClickables[0].first.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            currentStep = Step.INPUT_NAME
            retryCount = 0
            handler.postDelayed({ processCurrentStep() }, 1000)
        } else {
            // 备用：尝试按文字找
            val searchText = findNodeByText(root, "搜索")
            if (searchText != null) {
                val clickable = findClickableParent(searchText) ?: searchText
                clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                currentStep = Step.INPUT_NAME
                retryCount = 0
                handler.postDelayed({ processCurrentStep() }, 1000)
            } else {
                if (retryCount == 3) {
                    tip("找不到搜索按钮，顶栏可点击元素: ${topClickables.size}个")
                }
                scheduleRetry("找不到搜索按钮")
            }
        }
    }

    /**
     * 步骤2：在搜索框输入备注名
     * 搜索页顶部有 EditText，hint 是 "搜索本地或网络结果"
     */
    private fun handleInputName(root: AccessibilityNodeInfo) {
        val targetName = targetWechatName ?: return

        val editText = findNodeByClassName(root, "android.widget.EditText")
        if (editText != null) {
            editText.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            editText.performAction(AccessibilityNodeInfo.ACTION_CLICK)

            tip("步骤2/5：输入「$targetName」")
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, targetName)
            editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            currentStep = Step.CLICK_RESULT
            retryCount = 0
            handler.postDelayed({ processCurrentStep() }, 2000)
        } else {
            scheduleRetry("搜索框还没出现")
        }
    }

    /**
     * 步骤3：点击搜索结果中的联系人
     */
    private fun handleClickResult(root: AccessibilityNodeInfo) {
        val targetName = targetWechatName ?: return

        val resultNode = findNodeByText(root, targetName)
        if (resultNode != null) {
            // 排除搜索框本身（里面也有输入的文字）
            val isInEditText = isInsideEditText(resultNode)
            if (!isInEditText) {
                tip("步骤3/5：点击「$targetName」")
                val clickable = findClickableParent(resultNode) ?: resultNode
                clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                currentStep = Step.CLICK_PLUS
                retryCount = 0
                handler.postDelayed({ processCurrentStep() }, 2000)
                return
            }

            // 搜索框里的那个匹配，继续找其他的
            val allMatches = root.findAccessibilityNodeInfosByText(targetName)
            val resultMatch = allMatches?.firstOrNull { !isInsideEditText(it) }
            if (resultMatch != null) {
                tip("步骤3/5：点击「$targetName」")
                val clickable = findClickableParent(resultMatch) ?: resultMatch
                clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                currentStep = Step.CLICK_PLUS
                retryCount = 0
                handler.postDelayed({ processCurrentStep() }, 2000)
                return
            }
        }

        if (retryCount == 5) {
            val texts = collectTexts(root)
            tip("找不到「$targetName」，界面: $texts")
        }
        scheduleRetry("搜索结果还没出来")
    }

    /**
     * 步骤4：点击聊天界面底部的「+」按钮
     */
    private fun handleClickPlus(root: AccessibilityNodeInfo) {
        // 方式1：按描述找
        var plusBtn = findByDescription(root, "更多功能按钮")
            ?: findByDescription(root, "切换到按住说话")  // 有时候这个在附近
            ?: findByDescription(root, "更多功能")

        if (plusBtn != null) {
            tip("步骤4/5：点击 + 展开功能")
            val clickable = if (plusBtn.isClickable) plusBtn else findClickableParent(plusBtn) ?: plusBtn
            clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            currentStep = Step.CLICK_VIDEO_CALL
            retryCount = 0
            handler.postDelayed({ processCurrentStep() }, 1500)
            return
        }

        // 方式2：找聊天底部输入栏右侧的 + 按钮（按位置）
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val bottomClickables = mutableListOf<Pair<AccessibilityNodeInfo, Rect>>()
        traverseAll(root) { node ->
            if (node.isClickable) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                // 底部区域，输入栏附近
                if (rect.top > screenHeight * 0.85 && rect.left > screenWidth * 0.7) {
                    bottomClickables.add(Pair(node, rect))
                }
            }
        }

        if (bottomClickables.isNotEmpty()) {
            // 最右边的那个通常是 +
            bottomClickables.sortByDescending { it.second.left }
            tip("步骤4/5：点击右下角 + 按钮")
            bottomClickables[0].first.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            currentStep = Step.CLICK_VIDEO_CALL
            retryCount = 0
            handler.postDelayed({ processCurrentStep() }, 1500)
            return
        }

        if (retryCount == 3) {
            val descs = collectDescAndTexts(root)
            tip("找不到+，底部元素: $descs")
        }
        scheduleRetry("找不到 + 按钮")
    }

    /**
     * 步骤5：点击功能面板中的「视频通话」
     */
    private fun handleClickVideoCall(root: AccessibilityNodeInfo) {
        val videoBtn = findNodeByText(root, "视频通话")
        if (videoBtn != null) {
            tip("步骤5/5：发起视频通话！")
            val clickable = findClickableParent(videoBtn) ?: videoBtn
            clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            currentStep = Step.DONE
            retryCount = 0
            handler.postDelayed({ enableSpeaker() }, 3000)
        } else {
            if (retryCount == 3) {
                val texts = collectTexts(root)
                tip("找不到视频通话，面板: $texts")
            }
            scheduleRetry("找不到「视频通话」")
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
            currentStep = Step.IDLE
            retryCount = 0
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

    private fun findByDescription(root: AccessibilityNodeInfo, desc: String): AccessibilityNodeInfo? {
        return traverseFind(root) { it.contentDescription?.toString()?.contains(desc) == true }
    }

    private fun findNodeByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        return root.findAccessibilityNodeInfosByText(text)?.firstOrNull()
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

    private fun collectDescAndTexts(root: AccessibilityNodeInfo): String {
        val items = mutableListOf<String>()
        val screenHeight = resources.displayMetrics.heightPixels
        traverseAll(root) { node ->
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.top > screenHeight * 0.6) {
                node.text?.toString()?.let { if (it.isNotBlank() && it.length < 20) items.add(it) }
                node.contentDescription?.toString()?.let { if (it.isNotBlank() && it.length < 20) items.add("d:$it") }
            }
        }
        return items.take(10).joinToString(", ")
    }

    private fun traverseFind(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
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
        currentStep = Step.IDLE
    }
}
