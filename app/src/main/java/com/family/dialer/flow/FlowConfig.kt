package com.family.dialer.flow

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 流程配置的存取管理器
 * 使用 SharedPreferences + JSON 手动序列化（避免引入 Gson 依赖）
 */
object FlowConfig {

    private const val PREFS_NAME = "flow_config"
    private const val KEY_FLOW = "flow_steps"
    private const val KEY_TEST_CONTACT = "test_contact"

    /** 读取测试联系人 */
    fun getTestContact(context: Context): String {
        return prefs(context).getString(KEY_TEST_CONTACT, "") ?: ""
    }

    /** 保存测试联系人 */
    fun saveTestContact(context: Context, name: String) {
        prefs(context).edit().putString(KEY_TEST_CONTACT, name).apply()
    }

    /** 默认流程模板（8 步） */
    val DEFAULT_FLOW: List<FlowStep> = listOf(
        FlowStep(
            id = "launch",
            label = "打开微信",
            type = StepType.LAUNCH,
            editable = false,
            delayMs = 3000,
            hint = "自动启动微信 App"
        ),
        FlowStep(
            id = "search",
            label = "点击搜索按钮 🔍",
            type = StepType.TAP,
            editable = true,
            xPercent = 0.85f,
            yPercent = 0.06f,
            delayMs = 1500,
            hint = "微信主页右上角的放大镜图标"
        ),
        FlowStep(
            id = "paste",
            label = "点击键盘粘贴建议",
            type = StepType.PASTE,
            editable = true,
            xPercent = 0.10f,
            yPercent = 0.70f,
            delayMs = 1500,
            hint = "键盘上方的剪贴板粘贴建议（📋图标）"
        ),
        FlowStep(
            id = "search_btn",
            label = "点击键盘「搜索」",
            type = StepType.TAP,
            editable = true,
            xPercent = 0.90f,
            yPercent = 0.96f,
            delayMs = 2000,
            hint = "键盘右下角的搜索按钮"
        ),
        FlowStep(
            id = "select_contact",
            label = "点击搜索结果",
            type = StepType.FIND_TAP,
            editable = false,
            findText = "",  // 运行时替换为实际备注名
            delayMs = 2000,
            hint = "自动点击第一个匹配的联系人"
        ),
        FlowStep(
            id = "plus",
            label = "点击 + 按钮",
            type = StepType.TAP,
            editable = true,
            xPercent = 0.92f,
            yPercent = 0.94f,
            delayMs = 1500,
            hint = "聊天页面右下角的 + 按钮"
        ),
        FlowStep(
            id = "video_call",
            label = "点击「视频通话」",
            type = StepType.FIND_TAP,
            editable = true,
            findText = "视频通话",
            delayMs = 1500,
            hint = "功能面板中的视频通话选项"
        ),
        FlowStep(
            id = "confirm",
            label = "确认视频通话",
            type = StepType.FIND_TAP,
            editable = true,
            findText = "视频通话",
            delayMs = 1000,
            hint = "弹窗中的确认按钮"
        )
    )

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** 读取已保存的流程，不存在则返回默认流程 */
    fun getFlow(context: Context): List<FlowStep> {
        val json = prefs(context).getString(KEY_FLOW, null) ?: return DEFAULT_FLOW.toList()
        return try {
            parseFlow(json)
        } catch (e: Exception) {
            DEFAULT_FLOW.toList()
        }
    }

    /** 保存整个流程 */
    fun saveFlow(context: Context, steps: List<FlowStep>) {
        val json = serializeFlow(steps)
        prefs(context).edit().putString(KEY_FLOW, json).apply()
    }

    /** 更新某一步的坐标 */
    fun updateStepPosition(context: Context, stepId: String, xPercent: Float, yPercent: Float) {
        val steps = getFlow(context).toMutableList()
        val index = steps.indexOfFirst { it.id == stepId }
        if (index >= 0) {
            steps[index] = steps[index].copy(xPercent = xPercent, yPercent = yPercent)
            saveFlow(context, steps)
        }
    }

    /** 更新某一步的查找文字 */
    fun updateStepFindText(context: Context, stepId: String, findText: String) {
        val steps = getFlow(context).toMutableList()
        val index = steps.indexOfFirst { it.id == stepId }
        if (index >= 0) {
            steps[index] = steps[index].copy(findText = findText)
            saveFlow(context, steps)
        }
    }

    /** 恢复默认流程 */
    fun resetToDefault(context: Context) {
        prefs(context).edit().remove(KEY_FLOW).apply()
    }

    /** 检查用户是否已配置过流程 */
    fun isConfigured(context: Context): Boolean {
        return prefs(context).contains(KEY_FLOW)
    }

    // ---- JSON 序列化/反序列化 ----

    private fun serializeFlow(steps: List<FlowStep>): String {
        val arr = JSONArray()
        for (step in steps) {
            val obj = JSONObject().apply {
                put("id", step.id)
                put("label", step.label)
                put("type", step.type.name)
                put("editable", step.editable)
                put("delayMs", step.delayMs)
                put("hint", step.hint)
                if (step.xPercent != null) put("xPercent", step.xPercent.toDouble())
                if (step.yPercent != null) put("yPercent", step.yPercent.toDouble())
                if (step.findText != null) put("findText", step.findText)
            }
            arr.put(obj)
        }
        return arr.toString()
    }

    private fun parseFlow(json: String): List<FlowStep> {
        val arr = JSONArray(json)
        val steps = mutableListOf<FlowStep>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            steps.add(
                FlowStep(
                    id = obj.getString("id"),
                    label = obj.getString("label"),
                    type = StepType.valueOf(obj.getString("type")),
                    editable = obj.optBoolean("editable", false),
                    xPercent = if (obj.has("xPercent")) obj.getDouble("xPercent").toFloat() else null,
                    yPercent = if (obj.has("yPercent")) obj.getDouble("yPercent").toFloat() else null,
                    findText = obj.optString("findText", null),
                    delayMs = obj.optLong("delayMs", 1500),
                    hint = obj.optString("hint", "")
                )
            )
        }
        return steps
    }
}
