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

    companion object {
        var targetWechatName: String? = null
        var currentStep = Step.IDLE

        enum class Step {
            IDLE,
            OPEN_WECHAT,        // 等待微信主界面
            CLICK_SEARCH,       // 点击搜索
            INPUT_NAME,         // 输入备注名
            CLICK_RESULT,       // 点击搜索结果
            CLICK_PLUS,         // 点击聊天中的「+」
            CLICK_VIDEO_CALL,   // 点击「视频通话」
            DONE                // 完成
        }
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
            else -> {}
        }
    }

    /**
     * 微信主界面 → 点击搜索图标
     */
    private fun handleOpenWechat(root: AccessibilityNodeInfo) {
        // 微信主界面通常有「微信」tab，先确认在主界面
        val searchBtn = findNodeByDescription(root, "搜索")
            ?: findNodeByViewId(root, "com.tencent.mm:id/icon_search")
            ?: findNodeByViewId(root, "com.tencent.mm:id/kj")

        if (searchBtn != null) {
            searchBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            currentStep = Step.INPUT_NAME
            retryCount = 0
            // 延迟输入，等搜索框出现
            handler.postDelayed({ triggerRefresh() }, 800)
        } else {
            retryOrFail("找不到搜索按钮")
        }
    }

    private fun handleClickSearch(root: AccessibilityNodeInfo) {
        // 备用：如果 OPEN_WECHAT 已跳过此步骤
        handleOpenWechat(root)
    }

    /**
     * 搜索框 → 输入备注名
     */
    private fun handleInputName(root: AccessibilityNodeInfo) {
        val targetName = targetWechatName ?: return

        // 找到搜索输入框
        val editText = findNodeByClassName(root, "android.widget.EditText")
        if (editText != null) {
            // 输入文字
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, targetName)
            editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            currentStep = Step.CLICK_RESULT
            retryCount = 0
            // 延迟等搜索结果
            handler.postDelayed({ triggerRefresh() }, 1500)
        } else {
            retryOrFail("找不到搜索框")
        }
    }

    /**
     * 搜索结果 → 点击第一个联系人结果
     */
    private fun handleClickResult(root: AccessibilityNodeInfo) {
        val targetName = targetWechatName ?: return

        // 尝试找到搜索结果中的联系人
        val resultNode = findNodeByText(root, targetName)
        if (resultNode != null) {
            // 点击联系人结果
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

    /**
     * 聊天界面 → 点击「+」按钮
     */
    private fun handleClickPlus(root: AccessibilityNodeInfo) {
        // 微信聊天界面底部的「+」
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

    /**
     * 功能面板 → 点击「视频通话」
     */
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

            // 延迟开启扬声器
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

    // ---- 辅助方法 ----

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
        // 主动获取一次 root 触发处理
        val root = rootInActiveWindow ?: return
        when (currentStep) {
            Step.OPEN_WECHAT -> handleOpenWechat(root)
            Step.CLICK_SEARCH -> handleClickSearch(root)
            Step.INPUT_NAME -> handleInputName(root)
            Step.CLICK_RESULT -> handleClickResult(root)
            Step.CLICK_PLUS -> handleClickPlus(root)
            Step.CLICK_VIDEO_CALL -> handleClickVideoCall(root)
            else -> {}
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
