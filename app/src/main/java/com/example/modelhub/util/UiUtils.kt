package com.example.modelhub.util

import android.content.Context
import android.graphics.drawable.GradientDrawable

object UiUtils {
    fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }

    fun roundedBackground(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
        }
    }

    fun roundedStrokeBackground(color: Int, radius: Float, strokeColor: Int, strokeWidth: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
            setStroke(strokeWidth, strokeColor)
        }
    }
}
