plugins {
    id("com.android.application")
    id("com.google.android.gms.oss-licenses-plugin")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
    kotlin("android")
    id("kotlin-parcelize")
}

setupApp()

android {
    namespace = "com.github.shadowsocks"
    defaultConfig.applicationId = "com.github.shadowsocks"
}

dependencies {
    val cameraxVersion = "1.2.1"

    implementation("androidx.browser:browser:1.5.0-rc01")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:$lifecycleVersion")
    implementation ("com.google.code.gson:gson:2.8.8")
    implementation("com.google.mlkit:barcode-scanning:17.0.3")
    implementation("com.google.zxing:core:3.5.1")
    implementation("com.takisoft.preferencex:preferencex-simplemenu:1.1.0")
    implementation("com.twofortyfouram:android-plugin-api-for-locale:1.0.4")
    implementation("me.zhanghai.android.fastscroll:library:1.2.0")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation ("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.9.3")
    implementation ("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation ("com.squareup.okhttp3:okhttp:4.9.1")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.9.1")
    implementation("com.squareup.okhttp3:logging-interceptor:4.9.1")
    implementation("androidx.core:core-ktx:1.7.0")
    implementation("androidx.appcompat:appcompat:1.4.0")
    implementation("androidx.fragment:fragment-ktx:1.4.0")
    implementation("androidx.recyclerview:recyclerview:1.2.1")

}
