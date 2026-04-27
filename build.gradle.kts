// Top-level build file — configuración común a todos los módulos
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android)      apply false
    // ELIMINADO: libs.plugins.kotlin.compose — no usamos Jetpack Compose
}