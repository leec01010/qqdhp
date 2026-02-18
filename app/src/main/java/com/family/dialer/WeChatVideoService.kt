package com.family.dialer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
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
     * 步骤1：在微信主界面点击搜索
     */
    private fun handleOpenWechat(root: AccessibilityNodeInfo) {
        // 方式1：按 contentDescription 找
        val searchBtn = findClickableByDescription(root, "搜索")
            ?: findClickableByDescription(root, "搜寻")
            ?: findClickableByDescription(root, "Search")

        if (searchBtn != null) {
            tip("步骤1/5：点击搜索按钮")
            searchBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            currentStep = Step.INPUT_NAME
            retryCount = 0
            handler.postDelayed({ processCurrentStep() }, 1000)
            return
        }

        // 方式2：找右上角的图标按钮
        val topRightBtn = findTopRightClickable(root)
        if (topRightBtn != null) {
            tip("步骤1/5：点击右上角搜索图标")
            topRightBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            currentStep = Step.INPUT_NAME
            retryCount = 0
            handler.postDelayed({ processCurrentStep() }, 1000)
            return
        }

        // 方式3：收集所有节点的描述，帮助调试
        if (retryCount == 3) {
            val descs = collectDescriptions(root)
            tip("未找到搜索按钮，界面元素: $descs")
        }

        scheduleRetry("找不到搜索按钮")
    }

    /**
     * 步骤2：在搜索框输入备注名
     */
    private fun handleInputName(root: AccessibilityNodeInfo) {
        val targetName = targetWechatName ?: return

        val editText = findNodeByClassName(root, "android.widget.EditText")
        if (editText != null) {
            tip("步骤2/5：输入「$targetName」")
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, targetName)
            editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            currentStep = Step.CLICK_RESULT
            retryCount = 0
            handler.postDelayed({ processCurrentStep() }, 2000)
        } else {
            scheduleRetry("找不到搜索框")
        }
    }

    /**
     * 步骤3：点击搜索结果
     */
    private fun handleClickResult(root: AccessibilityNodeInfo) {
        val targetName = targetWechatName ?: return

        val resultNode = findNodeByText(root, targetName)
        if (resultNode != null) {
            tip("步骤3/5：点击联系人「$targetName」")
            val clickable = findClickableParent(resultNode) ?: resultNode
            clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            currentStep = Step.CLICK_PLUS
            retryCount = 0
            handler.postDelayed({ processCurrentStep() }, 2000)
        } else {
            scheduleRetry("找不到「$targetName」")
        }
    }

    /**
     * 步骤4：点击聊天界面的「+」按钮
     */
    private fun handleClickPlus(root: AccessibilityNodeInfo) {
        val plusBtn = findClickableByDescription(root, "更多功能按钮，已折叠")
            ?: findClickableByDescription(root, "更多功能按钮")
            ?: findClickableByDescription(root, "更多功能")
            ?: findClickableByDescription(root, "添加")
            ?: findClickableByDescription(root, "More")

        if (plusBtn != null) {
            tip("步骤4/5：点击「+」展开功能")
            plusBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            currentStep = Step.CLICK_VIDEO_CALL
            retryCount = 0
            handler.postDelayed({ processCurrentStep() }, 1500)
        } else {
            if (retryCount == 3) {
                val descs = collectDescriptions(root)
                tip("未找到+按钮，界面元素: $descs")
            }
            scheduleRetry("找不到 + 按钮")
        }
    }

    /**
     * 步骤5：点击「视频通话」
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

    /** 按 contentDescription 查找可点击节点 */
    private fun findClickableByDescription(root: AccessibilityNodeInfo, desc: String): AccessibilityNodeInfo? {
        val node = traverseFind(root) { n ->
            n.contentDescription?.toString()?.contains(desc) == true
        } ?: return null
        return if (node.isClickable) node else findClickableParent(node)
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

    /** 找右上角可点击的图标 */
    private fun findTopRightClickable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val screenWidth = resources.displayMetrics.widthPixels
        var result: AccessibilityNodeInfo? = null
        traverseAll(root) { node ->
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            if (rect.left > screenWidth * 0.7 && rect.top < 200 && node.isClickable) {
                val cn = node.className?.toString() ?: ""
                if (cn.contains("ImageView") || cn.contains("ImageButton")) {
                    result = node
                }
            }
        }
        return result
    }

    /** 收集界面上有效描述信息（调试用，只在找不到按钮时显示一次） */
    private fun collectDescriptions(root: AccessibilityNodeInfo): String {
        val descs = mutableListOf<String>()
        traverseAll(root) { node ->
            node.contentDescription?.toString()?.let {
                if (it.isNotBlank() && it.length < 30) descs.add(it)
            }
        }
        return descs.take(10).joinToString(", ")
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
