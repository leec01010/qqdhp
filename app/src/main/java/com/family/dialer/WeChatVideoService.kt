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

/**
 * 无障碍服务：自动操作微信完成视频通话
 *
 * 流程：打开微信 → 点搜索 → 输入备注名 → 点击结果 → 点「+」→ 点「视频通话」
 */
class WeChatVideoService : AccessibilityService() {

    enum class Step {
        IDLE,
        OPEN_WECHAT,
        CLICK_SEARCH,
        INPUT_NAME,
        CLICK_RESULT,
        CLICK_PLUS,
        CLICK_VIDEO_CALL,
        DONE
    }

    companion object {
        var targetWechatName: String? = null
        var currentStep = Step.IDLE
    }

    private val handler = Handler(Looper.getMainLooper())
    private var retryCount = 0
    private val MAX_RETRY = 15

    override fun onServiceConnected() {
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            packageNames = arrayOf("com.tencent.mm")
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 300
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || currentStep == Step.IDLE || currentStep == Step.DONE) return
        if (event.packageName?.toString() != "com.tencent.mm") return

        val root = rootInActiveWindow ?: return

        when (currentStep) {
            Step.OPEN_WECHAT -> handleOpenWechat(root)
            Step.CLICK_SEARCH -> handleClickSearch(root)
            Step.INPUT_NAME -> handleInputName(root)
            Step.CLICK_RESULT -> handleClickResult(root)
            Step.CLICK_PLUS -> handleClickPlus(root)
            Step.CLICK_VIDEO_CALL -> handleClickVideoCall(root)
            Step.IDLE, Step.DONE -> {}
        }
    }

    private fun handleOpenWechat(root: AccessibilityNodeInfo) {
        val searchBtn = findNodeByDescription(root, "搜索")
            ?: findNodeByViewId(root, "com.tencent.mm:id/icon_search")
            ?: findNodeByViewId(root, "com.tencent.mm:id/kj")

        if (searchBtn != null) {
            searchBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            currentStep = Step.INPUT_NAME
            retryCount = 0
            handler.postDelayed({ triggerRefresh() }, 800)
        } else {
            retryOrFail("找不到搜索按钮")
        }
    }

    private fun handleClickSearch(root: AccessibilityNodeInfo) {
        handleOpenWechat(root)
    }

    private fun handleInputName(root: AccessibilityNodeInfo) {
        val targetName = targetWechatName ?: return

        val editText = findNodeByClassName(root, "android.widget.EditText")
        if (editText != null) {
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, targetName)
            editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            currentStep = Step.CLICK_RESULT
            retryCount = 0
            handler.postDelayed({ triggerRefresh() }, 1500)
        } else {
            retryOrFail("找不到搜索框")
        }
    }

    private fun handleClickResult(root: AccessibilityNodeInfo) {
        val targetName = targetWechatName ?: return

        val resultNode = findNodeByText(root, targetName)
        if (resultNode != null) {
            var clickable = resultNode
            while (clickable != null && !clickable.isClickable) {
                clickable = clickable.parent
            }
            clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            currentStep = Step.CLICK_PLUS
            retryCount = 0
            handler.postDelayed({ triggerRefresh() }, 1500)
        } else {
            retryOrFail("找不到联系人「$targetName」")
        }
    }

    private fun handleClickPlus(root: AccessibilityNodeInfo) {
        val plusBtn = findNodeByDescription(root, "更多功能按钮，已折叠")
            ?: findNodeByDescription(root, "更多功能按钮")
            ?: findNodeByText(root, "+")
            ?: findNodeByViewId(root, "com.tencent.mm:id/bql")

        if (plusBtn != null) {
            var clickable = plusBtn
            while (clickable != null && !clickable.isClickable) {
                clickable = clickable.parent
            }
            clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            currentStep = Step.CLICK_VIDEO_CALL
            retryCount = 0
            handler.postDelayed({ triggerRefresh() }, 1000)
        } else {
            retryOrFail("找不到 + 按钮")
        }
    }

    private fun handleClickVideoCall(root: AccessibilityNodeInfo) {
        val videoBtn = findNodeByText(root, "视频通话")
        if (videoBtn != null) {
            var clickable = videoBtn
            while (clickable != null && !clickable.isClickable) {
                clickable = clickable.parent
            }
            clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            currentStep = Step.DONE
            retryCount = 0

            handler.postDelayed({
                enableSpeaker()
            }, 3000)
        } else {
            retryOrFail("找不到「视频通话」按钮")
        }
    }

    private fun enableSpeaker() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true
    }

    private fun retryOrFail(msg: String) {
        retryCount++
        if (retryCount > MAX_RETRY) {
            currentStep = Step.IDLE
            retryCount = 0
        } else {
            handler.postDelayed({ triggerRefresh() }, 1000)
        }
    }

    private fun triggerRefresh() {
        val root = rootInActiveWindow ?: return
        when (currentStep) {
            Step.OPEN_WECHAT -> handleOpenWechat(root)
            Step.CLICK_SEARCH -> handleClickSearch(root)
            Step.INPUT_NAME -> handleInputName(root)
            Step.CLICK_RESULT -> handleClickResult(root)
            Step.CLICK_PLUS -> handleClickPlus(root)
            Step.CLICK_VIDEO_CALL -> handleClickVideoCall(root)
            Step.IDLE, Step.DONE -> {}
        }
    }

    private fun findNodeByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val nodes = root.findAccessibilityNodeInfosByText(text)
        return nodes?.firstOrNull()
    }

    private fun findNodeByDescription(root: AccessibilityNodeInfo, desc: String): AccessibilityNodeInfo? {
        return traverseFind(root) { node ->
            node.contentDescription?.toString()?.contains(desc) == true
        }
    }

    private fun findNodeByViewId(root: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
        val nodes = root.findAccessibilityNodeInfosByViewId(id)
        return nodes?.firstOrNull()
    }

    private fun findNodeByClassName(root: AccessibilityNodeInfo, className: String): AccessibilityNodeInfo? {
        return traverseFind(root) { node ->
            node.className?.toString() == className
        }
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

    override fun onInterrupt() {
        currentStep = Step.IDLE
    }
}
