package app.momiji.tv.agent

import android.content.Context
import android.graphics.PixelFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import app.momiji.tv.R

class Overlay(private val context: Context) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private var view: TextView? = null
    private val hide = Runnable { remove() }

    fun show(text: String, linger: Long = 0) {
        val label = view ?: create().also { view = it }
        label.text = text
        label.alpha = 1f
        label.removeCallbacks(hide)
        if (linger > 0) label.postDelayed(hide, linger)
    }

    fun setHidden(hidden: Boolean) {
        view?.alpha = if (hidden) 0f else 1f
    }

    fun remove() {
        view?.let {
            it.removeCallbacks(hide)
            try {
                wm.removeView(it)
            } catch (e: IllegalArgumentException) {
            }
        }
        view = null
    }

    private fun create(): TextView {
        val pad = (20 * density).toInt()
        val label = TextView(context).apply {
            setBackgroundResource(R.drawable.pill)
            setTextColor(context.getColor(R.color.cream))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setPadding(pad, pad / 2, pad + pad / 4, pad / 2)
            compoundDrawablePadding = pad / 2
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_leaf, 0, 0, 0)
            maxWidth = (720 * density).toInt()
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (40 * density).toInt()
        }
        wm.addView(label, params)
        return label
    }
}
