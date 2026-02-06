package com.facefusion.app.ui.main

data class MainUiState(
    val baseUrl: String = "",
    val token: String = "",
    val authStatus: String = "Auth: not set",
    val healthOnline: Boolean? = null,
    val statusText: String = "Status: idle",
    val jobStatusText: String = "Job: n/a",
    val progress: Int = 0,
    val isStartEnabled: Boolean = true,
    val startButtonText: String = "Start job",
    val activeJobId: String? = null,
    val lastJobId: String? = null,
    val resultImagePath: String? = null,
    val resultVideoPath: String? = null,
)
