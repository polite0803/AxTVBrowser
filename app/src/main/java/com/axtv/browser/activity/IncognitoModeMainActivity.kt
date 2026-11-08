package com.axtv.browser.activity

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AlertDialog
import com.axtv.browser.R
import com.axtv.browser.TVBro
import com.axtv.browser.activity.main.MainActivity

//Same as MainActivity but runs in separate process
//and store all WebView data separately
class IncognitoModeMainActivity: MainActivity() {
    companion object {
        private val TAG = MainActivity::class.java.simpleName
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "onCreate")
        super.onCreate(savedInstanceState)
        if (!config.incognitoModeHintSuppress) {
            showIncognitoModeHintDialog()
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            TVBro.exitProcessAfterMainActivityFinish()
        }
        super.onDestroy()
    }

    private fun showIncognitoModeHintDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.incognito_mode)
            .setIcon(R.drawable.ic_incognito)
            .setMessage(R.string.incognito_mode_hint)
            .setPositiveButton(android.R.string.ok) { dialog, _ -> dialog.dismiss() }
            .setNeutralButton(R.string.don_t_show_again) { dialog, _ ->
                config.incognitoModeHintSuppress = true
                dialog.dismiss()
            }
            .show()
    }
}