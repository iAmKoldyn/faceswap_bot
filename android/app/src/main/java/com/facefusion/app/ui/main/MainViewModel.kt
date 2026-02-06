package com.facefusion.app.ui.main

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.facefusion.app.BuildConfig
import com.facefusion.app.data.local.SettingsDataStore
import com.facefusion.app.data.media.MediaManager
import com.facefusion.app.data.network.JobResponse
import com.facefusion.app.data.repository.FaceFusionRepository
import com.facefusion.app.data.repository.FaceFusionRepositoryImpl
import com.facefusion.app.domain.result.AppResult
import com.facefusion.app.domain.result.ErrorType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class MainViewModel(
    application: Application,
    private val repository: FaceFusionRepository = FaceFusionRepositoryImpl(application),
    private val settingsDataStore: SettingsDataStore = SettingsDataStore(application),
    private val mediaManager: MediaManager = MediaManager(application),
) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState = _uiState.asStateFlow()

    private val _effect = MutableSharedFlow<MainUiEffect>()
    val effect = _effect.asSharedFlow()

    private var activeJobId: String? = null
    private var isJobRunning: Boolean = false
    private var currentResultImageFile: File? = null
    private var currentResultVideoFile: File? = null
    private var downloadingJobId: String? = null

    private var authCheckJob: Job? = null
    private var healthMonitorJob: Job? = null
    private var sseObserverJob: Job? = null
    private var statusWatchdogJob: Job? = null

    init {
        observeSettings()
    }

    fun onAction(action: MainUiAction) {
        when (action) {
            is MainUiAction.BaseUrlChanged -> onBaseUrlChanged(action.value)
            is MainUiAction.TokenChanged -> onTokenChanged(action.value)
            is MainUiAction.StartJob -> startJob(
                sourceUri = action.sourceUri,
                targetUri = action.targetUri,
                mode = action.mode,
                isPhotoVideoMode = action.isPhotoVideoMode,
            )
            is MainUiAction.CheckJobStatus -> checkJobStatus(action.jobIdInput)
            MainUiAction.SaveResultImage -> saveResultImage()
            MainUiAction.SaveResultVideo -> saveResultVideo()
        }
    }

    private fun onBaseUrlChanged(value: String) {
        viewModelScope.launch {
            settingsDataStore.setBaseUrl(value.trim())
        }
    }

    private fun onTokenChanged(value: String) {
        viewModelScope.launch {
            settingsDataStore.setToken(value.trim())
        }
    }

    private fun startJob(sourceUri: Uri?, targetUri: Uri?, mode: String, isPhotoVideoMode: Boolean) {
        if (isJobRunning) {
            emitToast("Wait until current job is finished")
            return
        }
        val source = sourceUri
        val target = targetUri
        if (source == null || target == null) {
            emitToast("Select source and target")
            return
        }
        if (!mediaManager.isImageUri(source)) {
            emitToast("Source must be a photo (jpg/jpeg/png)")
            return
        }
        if (!mediaManager.isTargetAllowed(target, isPhotoVideoMode)) {
            emitToast("Target must be ${mediaManager.targetErrorText(isPhotoVideoMode)}")
            return
        }

        val state = _uiState.value
        val baseUrl = state.baseUrl.trim()
        val token = state.token.trim()
        if (baseUrl.isEmpty()) {
            emitToast("Base URL is empty")
            return
        }
        if (token.isEmpty()) {
            emitToast("Paste access token")
            return
        }

        resetResultState()
        setRunningState(true)
        updateState {
            it.copy(
                statusText = "Status: starting",
                progress = 0,
            )
        }

        viewModelScope.launch {
            val sourceMime = mediaManager.getMime(source)
            val targetMime = mediaManager.getMime(target)
            val sourceFileResult = mediaManager.copyToCache(source, "source", sourceMime)
            val targetFileResult = mediaManager.copyToCache(target, "target", targetMime)
            val sourceFile = (sourceFileResult as? AppResult.Success)?.data
            val targetFile = (targetFileResult as? AppResult.Success)?.data
            if (sourceFile == null || targetFile == null) {
                val message = (sourceFileResult as? AppResult.Error)?.message
                    ?: (targetFileResult as? AppResult.Error)?.message
                    ?: "Failed to prepare files"
                updateState { it.copy(statusText = "Error: $message") }
                setRunningState(false)
                return@launch
            }

            when (val createResult = repository.createJob(baseUrl, token, mode)) {
                is AppResult.Error -> {
                    handleError(createResult, "Error: failed to create job")
                    setRunningState(false)
                }
                is AppResult.Success -> {
                    val job = createResult.data
                    activeJobId = job.jobId
                    updateState {
                        it.copy(
                            activeJobId = job.jobId,
                            lastJobId = job.jobId,
                            statusText = "Status: created ${job.jobId}",
                            jobStatusText = "Job: ${job.status}",
                        )
                    }
                    if (!uploadAndSubmit(baseUrl, token, job, sourceFile, sourceMime, targetFile, targetMime)) {
                        setRunningState(false)
                        return@launch
                    }
                    startObservingJob(baseUrl, token, job.jobId)
                }
            }
        }
    }

    private fun checkJobStatus(jobIdInput: String) {
        val jobId = jobIdInput.trim().ifEmpty { _uiState.value.lastJobId.orEmpty() }
        if (jobId.isBlank()) {
            emitToast("Job ID is empty")
            return
        }
        val state = _uiState.value
        val baseUrl = state.baseUrl.trim()
        val token = state.token.trim()
        if (baseUrl.isEmpty() || token.isEmpty()) {
            emitToast("Base URL and token are required")
            return
        }
        updateState { it.copy(jobStatusText = "Job: checking") }
        viewModelScope.launch {
            when (val result = repository.getJob(baseUrl, token, jobId)) {
                is AppResult.Error -> handleError(result, "Job: error")
                is AppResult.Success -> {
                    val job = result.data
                    val formatted = formatStatus(job.status, job.stage)
                    updateState {
                        it.copy(
                            jobStatusText = "Job: $formatted (${job.progress}%)",
                            statusText = "Status: $formatted",
                            progress = job.progress,
                            lastJobId = job.jobId,
                        )
                    }
                }
            }
        }
    }

    private fun saveResultImage() {
        val imageFile = currentResultImageFile
        if (imageFile == null || !imageFile.exists()) {
            emitToast("No image result to save")
            return
        }
        viewModelScope.launch {
            when (val result = mediaManager.saveImageToGallery(imageFile)) {
                is AppResult.Success -> emitToast("Saved to gallery")
                is AppResult.Error -> emitToast(result.message ?: "Failed to save image")
            }
        }
    }

    private fun saveResultVideo() {
        val videoFile = currentResultVideoFile
        if (videoFile == null || !videoFile.exists()) {
            emitToast("No video result to save")
            return
        }
        viewModelScope.launch {
            when (val result = mediaManager.saveVideoToGallery(videoFile)) {
                is AppResult.Success -> emitToast("Video saved to gallery")
                is AppResult.Error -> emitToast(result.message ?: "Failed to save video")
            }
        }
    }

    fun isImageUri(uri: Uri): Boolean = mediaManager.isImageUri(uri)
    fun isVideoUri(uri: Uri): Boolean = mediaManager.isVideoUri(uri)
    fun isTargetAllowed(uri: Uri, isPhotoVideoMode: Boolean): Boolean = mediaManager.isTargetAllowed(uri, isPhotoVideoMode)
    fun targetErrorText(isPhotoVideoMode: Boolean): String = mediaManager.targetErrorText(isPhotoVideoMode)
    fun extractVideoPreviewFrame(uri: Uri) = mediaManager.extractVideoPreviewFrame(uri)

    private fun observeSettings() {
        viewModelScope.launch {
            settingsDataStore.baseUrl.collectLatest { value ->
                val effective = if (value.isBlank()) BuildConfig.DEFAULT_BASE_URL else value
                updateState { it.copy(baseUrl = effective) }
                startHealthMonitor(effective)
                scheduleAuthCheck()
            }
        }
        viewModelScope.launch {
            settingsDataStore.token.collectLatest { value ->
                updateState {
                    it.copy(
                        token = value,
                        authStatus = if (value.isBlank()) "Auth: not set" else "Auth: token set",
                    )
                }
                scheduleAuthCheck()
            }
        }
    }

    private fun scheduleAuthCheck() {
        authCheckJob?.cancel()
        authCheckJob = viewModelScope.launch {
            delay(400)
            val state = _uiState.value
            val baseUrl = state.baseUrl.trim()
            val token = state.token.trim()
            if (baseUrl.isEmpty() || token.isEmpty()) {
                return@launch
            }
            when (val verify = repository.verify(baseUrl, token)) {
                is AppResult.Success -> updateState { it.copy(authStatus = "Auth: ok (${verify.data})") }
                is AppResult.Error -> updateState {
                    it.copy(
                        authStatus = if (verify.type.name == "AUTH") "Auth: unauthorized" else "Auth: error",
                    )
                }
            }
        }
    }

    private fun startHealthMonitor(baseUrl: String) {
        healthMonitorJob?.cancel()
        if (baseUrl.isBlank()) {
            updateState { it.copy(healthOnline = null) }
            return
        }
        healthMonitorJob = viewModelScope.launch {
            while (isActive) {
                when (val health = repository.health(baseUrl)) {
                    is AppResult.Success -> updateState { it.copy(healthOnline = health.data) }
                    is AppResult.Error -> updateState { it.copy(healthOnline = false) }
                }
                delay(10000)
            }
        }
    }

    private suspend fun uploadAndSubmit(
        baseUrl: String,
        token: String,
        job: JobResponse,
        sourceFile: File,
        sourceMime: String?,
        targetFile: File,
        targetMime: String?,
    ): Boolean {
        updateState { it.copy(statusText = "Status: uploading source") }
        when (val sourceResult = repository.uploadSource(baseUrl, token, job.jobId, sourceFile, sourceMime)) {
            is AppResult.Error -> {
                handleError(sourceResult, "Error: source upload failed")
                return false
            }
            is AppResult.Success -> Unit
        }

        updateState { it.copy(statusText = "Status: uploading target") }
        when (val targetResult = repository.uploadTarget(baseUrl, token, job.jobId, targetFile, targetMime)) {
            is AppResult.Error -> {
                handleError(targetResult, "Error: target upload failed")
                return false
            }
            is AppResult.Success -> Unit
        }

        updateState { it.copy(statusText = "Status: submitting") }
        when (val submitResult = repository.submit(baseUrl, token, job.jobId)) {
            is AppResult.Error -> {
                handleError(submitResult, "Error: submit failed")
                return false
            }
            is AppResult.Success -> {
                updateState {
                    it.copy(
                        jobStatusText = "Job: ${submitResult.data.status}",
                    )
                }
            }
        }
        return true
    }

    private fun startObservingJob(baseUrl: String, token: String, jobId: String) {
        sseObserverJob?.cancel()
        statusWatchdogJob?.cancel()

        sseObserverJob = viewModelScope.launch {
            repository.observeJobEvents(baseUrl, token, jobId).collectLatest { result ->
                when (result) {
                    is AppResult.Success -> handleJobUpdate(baseUrl, token, result.data)
                    is AppResult.Error -> {
                        if (activeJobId == jobId) {
                            updateState { it.copy(statusText = "Status: SSE reconnecting...") }
                        }
                    }
                }
            }
        }

        statusWatchdogJob = viewModelScope.launch {
            while (isActive && activeJobId == jobId) {
                when (val result = repository.getJob(baseUrl, token, jobId)) {
                    is AppResult.Success -> handleJobUpdate(baseUrl, token, result.data)
                    is AppResult.Error -> Unit
                }
                delay(2000)
            }
        }
    }

    private fun handleJobUpdate(baseUrl: String, token: String, job: JobResponse) {
        if (activeJobId != job.jobId) {
            return
        }
        val formatted = formatStatus(job.status, job.stage)
        updateState {
            it.copy(
                statusText = "Status: $formatted",
                jobStatusText = "Job: $formatted (${job.progress}%)",
                progress = job.progress,
            )
        }

        when (job.status) {
            "completed" -> {
                activeJobId = null
                updateState {
                    it.copy(
                        statusText = "Status: completed, downloading...",
                        progress = 100,
                    )
                }
                launchResultDownload(baseUrl, token, job.jobId, job.targetKind)
            }
            "failed", "cancelled" -> {
                activeJobId = null
                setRunningState(false)
            }
        }
    }

    private fun launchResultDownload(baseUrl: String, token: String, jobId: String, targetKind: String) {
        if (downloadingJobId == jobId) {
            return
        }
        downloadingJobId = jobId
        viewModelScope.launch {
            when (val result = repository.downloadResult(baseUrl, token, jobId, targetKind)) {
                is AppResult.Success -> {
                    val file = result.data
                    if (targetKind == "video") {
                        currentResultVideoFile = file
                        currentResultImageFile = null
                        updateState {
                            it.copy(
                                resultVideoPath = file.absolutePath,
                                resultImagePath = null,
                                statusText = "Status: completed",
                            )
                        }
                    } else {
                        currentResultImageFile = file
                        currentResultVideoFile = null
                        updateState {
                            it.copy(
                                resultImagePath = file.absolutePath,
                                resultVideoPath = null,
                                statusText = "Status: completed",
                            )
                        }
                    }
                }
                is AppResult.Error -> {
                    updateState {
                        it.copy(
                            statusText = "Error: ${result.message ?: "download failed"}",
                        )
                    }
                }
            }
            downloadingJobId = null
            setRunningState(false)
        }
    }

    private fun resetResultState() {
        currentResultImageFile = null
        currentResultVideoFile = null
        updateState { it.copy(resultImagePath = null, resultVideoPath = null) }
    }

    private fun setRunningState(running: Boolean) {
        isJobRunning = running
        updateState {
            it.copy(
                isStartEnabled = !running,
                startButtonText = if (running) "Processing..." else "Start job",
            )
        }
    }

    private fun handleError(error: AppResult.Error, fallbackMessage: String) {
        updateState {
            it.copy(statusText = error.message?.let { msg -> "Error: $msg" } ?: fallbackMessage)
        }
        if (error.type == ErrorType.AUTH) {
            updateState { it.copy(authStatus = "Auth: unauthorized") }
        }
    }

    private fun formatStatus(status: String, stage: String?): String {
        val s = status.trim()
        val st = stage?.trim().orEmpty()
        if (st.isBlank() || st.equals(s, ignoreCase = true)) {
            return s
        }
        return "$s $st"
    }

    private fun updateState(update: (MainUiState) -> MainUiState) {
        _uiState.update(update)
    }

    private fun emitToast(message: String) {
        viewModelScope.launch {
            _effect.emit(MainUiEffect.Toast(message))
        }
    }

    override fun onCleared() {
        authCheckJob?.cancel()
        healthMonitorJob?.cancel()
        sseObserverJob?.cancel()
        statusWatchdogJob?.cancel()
        super.onCleared()
    }
}

class MainViewModelFactory(
    private val application: Application,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            return MainViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
