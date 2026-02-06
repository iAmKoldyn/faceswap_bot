package com.facefusion.app.ui.main

sealed interface MainUiEffect {
    data class Toast(val message: String) : MainUiEffect
}
