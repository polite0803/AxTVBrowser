-keep class com.axtv.browser.webengine.gecko.GeckoWebEngine { *; }

-keepclassmembers class org.mozilla.geckoview.** {
    *** mDisplay;
    *** sTreatMouseAsTouch;
}
