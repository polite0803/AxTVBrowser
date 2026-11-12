package com.axtv.browser.activity.main.view

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import com.axtv.browser.databinding.ViewPhoneModeRecoveryToastBinding

/**
 * On a TV that got the phone interface by accident: a toast with a button that can be pressed with
 * the remote, to get the TV interface back.
 */
class PhoneModeRecoveryToast @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    interface Callback {
        fun restoreTvInterface()
    }

    private val vb = ViewPhoneModeRecoveryToastBinding.inflate(LayoutInflater.from(context), this, true)
    private var callback: Callback? = null

    var isShowing = false
        private set

    init {
        vb.vbAction.setOnClickListener { restore() }
        // A touch away from the button dismisses it; Back and the page keep working as before.
        setOnClickListener { dismiss() }
    }

    /** The shell tells the toast what to do; the toast does nothing before that */
    fun setCallbacks(callback: Callback) {
        this.callback = callback
    }

    /** Shows it, and puts the focus on the button so the remote's OK presses it right away */
    fun show() {
        if (isShowing) return
        visibility = View.VISIBLE
        isShowing = true
        alpha = 0f
        animate().alpha(1f).setDuration(200).start()
        // The shell shows the toast before the window is attached; focus takes effect after the layout.
        post { vb.vbAction.requestFocus() }
    }

    fun hide() {
        if (!isShowing) return
        isShowing = false
        visibility = View.GONE
        alpha = 1f
    }

    private fun restore() {
        isShowing = false
        callback?.restoreTvInterface()
    }

    private fun dismiss() {
        hide()
    }
}
