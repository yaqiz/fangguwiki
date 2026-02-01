// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false // 必须在这里声明
    alias(libs.plugins.kotlin.compose) apply false
}