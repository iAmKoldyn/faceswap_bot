package com.facefusion.app.ui.main

import android.net.Uri

sealed interface MainUiAction {
    data class BaseUrlChanged(val value: String) : MainUiAction
    data class TokenChanged(val value: String) : MainUiAction
    data class StartJob(
        val sourceUri: Uri?,
        val targetUri: Uri?,
        val mode: String,
        val isPhotoVideoMode: Boolean,
    ) : MainUiAction

    data class CheckJobStatus(val jobIdInput: String) : MainUiAction
    data object SaveResultImage : MainUiAction
    data object SaveResultVideo : MainUiAction
}
