package com.homepodplus.app

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * 线性马达触觉反馈控制工具。
 *
 * 优先调用 View.performHapticFeedback 触发系统底层的线性马达波形映射；
 * 当 View 反馈被系统设置阻断或返回 false 时，无缝回退至 Vibrator Predefined VibrationEffect，
 * 确保在主流 Android 机型（高通/天玑、Pixel、小米 HyperOS、一加/OPPO、三星等）的 X 轴/Z 轴线性马达上均能提供干脆、质感出众的振动回馈。
 */
object AppHaptics {

    /**
     * 轻微触感 (Tick)：用于 Tab 切换、折叠小箭头切换、分段单选 Chip、开关切换等
     */
    fun tick(view: View?) {
        if (view == null) return
        val handled = view.performHapticFeedback(
            HapticFeedbackConstants.CLOCK_TICK,
            HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
        )
        if (!handled) {
            fallbackVibrate(view.context, VibrationEffect.EFFECT_TICK)
        }
    }

    /**
     * 标准按键触感 (Click)：用于普通按钮点击、弹窗按钮等
     */
    fun click(view: View?) {
        if (view == null) return
        val handled = view.performHapticFeedback(
            HapticFeedbackConstants.KEYBOARD_TAP,
            HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
        )
        if (!handled) {
            fallbackVibrate(view.context, VibrationEffect.EFFECT_CLICK)
        }
    }

    /**
     * 确认/重触感 (Confirm / Heavy Click)：用于“开始/停止投送”、保存设置、绑定设备等关键操作
     */
    fun confirm(view: View?) {
        if (view == null) return
        val feedbackConstant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.KEYBOARD_TAP
        }
        val handled = view.performHapticFeedback(
            feedbackConstant,
            HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
        )
        if (!handled) {
            fallbackVibrate(view.context, VibrationEffect.EFFECT_HEAVY_CLICK)
        }
    }

    private fun fallbackVibrate(context: Context, effectId: Int) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator != null && vibrator.hasVibrator()) {
                val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    VibrationEffect.createPredefined(effectId)
                } else {
                    @Suppress("DEPRECATION")
                    VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE)
                }
                vibrator.vibrate(effect)
            }
        } catch (_: Exception) {
        }
    }
}
