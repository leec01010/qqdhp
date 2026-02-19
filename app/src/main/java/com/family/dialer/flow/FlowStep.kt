package com.family.dialer.flow

/**
 * 流程步骤类型
 */
enum class StepType {
    /** 启动 App */
    LAUNCH,
    /** 按坐标点击屏幕（用户可录制） */
    TAP,
    /** 从剪贴板粘贴文字（联系人备注名） */
    PASTE,
    /** 按文字查找并点击 */
    FIND_TAP,
    /** 等待 */
    DELAY
}

/**
 * 单个流程步骤的数据模型
 *
 * @param id        唯一标识，如 "search", "plus"
 * @param label     步骤名称，如 "点击搜索按钮"
 * @param type      步骤类型
 * @param editable  用户是否可微调
 * @param xPercent  TAP 类型：屏幕 X 百分比 (0.0~1.0)
 * @param yPercent  TAP 类型：屏幕 Y 百分比 (0.0~1.0)
 * @param findText  FIND_TAP 类型：要查找的文字
 * @param delayMs   步骤执行后等待的毫秒数
 * @param hint      编辑器里显示的提示文字
 */
data class FlowStep(
    val id: String,
    val label: String,
    val type: StepType,
    val editable: Boolean = false,
    val xPercent: Float? = null,
    val yPercent: Float? = null,
    val findText: String? = null,
    val delayMs: Long = 1500,
    val hint: String = ""
)
