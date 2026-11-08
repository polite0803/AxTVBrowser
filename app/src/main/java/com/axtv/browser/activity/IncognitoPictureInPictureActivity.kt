package com.axtv.browser.activity

import com.axtv.browser.activity.main.MainActivity

/** The picture-in-picture window of the incognito process: the player goes back to its browser activity */
class IncognitoPictureInPictureActivity : PictureInPictureActivity() {
    override val browserActivity: Class<out MainActivity> get() = IncognitoModeMainActivity::class.java
}
