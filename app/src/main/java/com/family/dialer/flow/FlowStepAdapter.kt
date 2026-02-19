package com.family.dialer.flow

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.family.dialer.R

class FlowStepAdapter(
    private val onRecordClick: (FlowStep) -> Unit
) : RecyclerView.Adapter<FlowStepAdapter.ViewHolder>() {

    private val steps = mutableListOf<FlowStep>()

    fun submitList(list: List<FlowStep>) {
        steps.clear()
        steps.addAll(list)
        notifyDataSetChanged()
    }

    fun getSteps(): List<FlowStep> = steps.toList()

    /** 更新某个步骤的坐标 */
    fun updatePosition(stepId: String, xPercent: Float, yPercent: Float) {
        val index = steps.indexOfFirst { it.id == stepId }
        if (index >= 0) {
            steps[index] = steps[index].copy(xPercent = xPercent, yPercent = yPercent)
            notifyItemChanged(index)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_flow_step, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(steps[position], position)
    }

    override fun getItemCount(): Int = steps.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvStepNumber: TextView = view.findViewById(R.id.tvStepNumber)
        private val tvLabel: TextView = view.findViewById(R.id.tvLabel)
        private val tvEditable: TextView = view.findViewById(R.id.tvEditable)
        private val tvHint: TextView = view.findViewById(R.id.tvHint)
        private val tvCoordInfo: TextView = view.findViewById(R.id.tvCoordInfo)
        private val layoutFindText: LinearLayout = view.findViewById(R.id.layoutFindText)
        private val etFindText: EditText = view.findViewById(R.id.etFindText)
        private val btnRecord: Button = view.findViewById(R.id.btnRecord)

        fun bind(step: FlowStep, position: Int) {
            tvStepNumber.text = "${position + 1}"
            tvLabel.text = step.label
            tvHint.text = step.hint

            // 可编辑标记
            tvEditable.visibility = if (step.editable) View.VISIBLE else View.GONE

            // 步骤圆圈颜色: 可编辑=绿色, 固定=蓝色
            val bgColor = if (step.editable) Color.parseColor("#07C160") else Color.parseColor("#1976D2")
            val bg = tvStepNumber.background
            bg.setTint(bgColor)

            // 固定步骤降低透明度
            itemView.alpha = if (step.editable) 1.0f else 0.7f

            // TAP 类型：显示坐标信息和录制按钮
            if (step.type == StepType.TAP) {
                if (step.xPercent != null && step.yPercent != null) {
                    tvCoordInfo.text = "当前坐标：X=${(step.xPercent * 100).toInt()}%  Y=${(step.yPercent * 100).toInt()}%"
                    tvCoordInfo.visibility = View.VISIBLE
                } else {
                    tvCoordInfo.text = "未设置坐标"
                    tvCoordInfo.visibility = View.VISIBLE
                }
                if (step.editable) {
                    btnRecord.visibility = View.VISIBLE
                    btnRecord.setOnClickListener { onRecordClick(step) }
                } else {
                    btnRecord.visibility = View.GONE
                }
            } else {
                tvCoordInfo.visibility = View.GONE
                btnRecord.visibility = View.GONE
            }

            // FIND_TAP 可编辑：显示文字编辑框
            if (step.type == StepType.FIND_TAP && step.editable) {
                layoutFindText.visibility = View.VISIBLE
                etFindText.setText(step.findText ?: "")
                etFindText.setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) {
                        val newText = etFindText.text.toString().trim()
                        if (newText.isNotEmpty() && newText != step.findText) {
                            val index = steps.indexOfFirst { it.id == step.id }
                            if (index >= 0) {
                                steps[index] = steps[index].copy(findText = newText)
                            }
                        }
                    }
                }
            } else {
                layoutFindText.visibility = View.GONE
            }
        }
    }
}
