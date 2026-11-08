plugins {
    id("axtv.android.library")
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.axtv.browser.common"
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.ktx)

    testImplementation(libs.junit)
}
