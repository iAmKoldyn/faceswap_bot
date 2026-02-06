package com.facefusion.app

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.VideoView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import com.facefusion.app.ui.main.MainUiEffect
import com.facefusion.app.ui.main.MainUiAction
import com.facefusion.app.ui.main.MainUiState
import com.facefusion.app.ui.main.MainViewModel
import com.facefusion.app.ui.main.MainViewModelFactory
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var viewModel: MainViewModel

    private lateinit var baseUrlEdit: EditText
    private lateinit var accessTokenEdit: EditText
    private lateinit var editJobId: EditText
    private lateinit var radioMode: RadioGroup
    private lateinit var btnTakeSelfie: Button
    private lateinit var btnPickTarget: Button
    private lateinit var btnStart: Button
    private lateinit var btnCheckJob: Button
    private lateinit var txtSource: TextView
    private lateinit var txtTarget: TextView
    private lateinit var txtStatus: TextView
    private lateinit var txtAuthStatus: TextView
    private lateinit var txtHealth: TextView
    private lateinit var txtJobStatus: TextView
    private lateinit var imgSourcePreview: ImageView
    private lateinit var imgTargetPreview: ImageView
    private lateinit var resultImageContainer: FrameLayout
    private lateinit var imgResult: ImageView
    private lateinit var btnSaveResult: ImageButton
    private lateinit var resultVideoContainer: FrameLayout
    private lateinit var videoResult: VideoView
    private lateinit var btnSaveVideoResult: ImageButton
    private lateinit var progressBar: ProgressBar

    private var sourceUri: Uri? = null
    private var targetUri: Uri? = null
    private var pendingSelfieUri: Uri? = null
    private var suspendTextCallbacks = false
    private var renderedImagePath: String? = null
    private var renderedVideoPath: String? = null

    private val pickSourceGalleryLauncher = registerForActivityResult(PickVisualMedia()) { uri ->
        uri?.let { handleSelectedSource(it, "Source: selected (gallery)") }
    }

    private val pickSourceFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { handleSelectedSource(it, "Source: selected (files)") }
    }

    private val pickTargetGalleryLauncher = registerForActivityResult(PickVisualMedia()) { uri ->
        uri?.let { handleSelectedTarget(it, "Target: selected (gallery)") }
    }

    private val pickTargetFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { handleSelectedTarget(it, "Target: selected (files)") }
    }

    private val takeSelfieLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (!success) {
            return@registerForActivityResult
        }
        val uri = pendingSelfieUri ?: return@registerForActivityResult
        sourceUri = uri
        txtSource.text = "Source: selfie"
        showSourcePreview(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewModel = ViewModelProvider(
            this,
            MainViewModelFactory(application),
        )[MainViewModel::class.java]

        bindViews()
        bindActions()
        observeViewModel()
        updateActionButtonForMode()
    }

    private fun bindViews() {
        baseUrlEdit = findViewById(R.id.editBaseUrl)
        accessTokenEdit = findViewById(R.id.editAccessToken)
        editJobId = findViewById(R.id.editJobId)
        radioMode = findViewById(R.id.radioMode)
        btnTakeSelfie = findViewById(R.id.btnTakeSelfie)
        btnPickTarget = findViewById(R.id.btnPickTarget)
        btnStart = findViewById(R.id.btnStart)
        btnCheckJob = findViewById(R.id.btnCheckJob)
        txtSource = findViewById(R.id.txtSource)
        txtTarget = findViewById(R.id.txtTarget)
        txtStatus = findViewById(R.id.txtStatus)
        txtAuthStatus = findViewById(R.id.txtAuthStatus)
        txtHealth = findViewById(R.id.txtHealth)
        txtJobStatus = findViewById(R.id.txtJobStatus)
        imgSourcePreview = findViewById(R.id.imgSourcePreview)
        imgTargetPreview = findViewById(R.id.imgTargetPreview)
        resultImageContainer = findViewById(R.id.resultImageContainer)
        imgResult = findViewById(R.id.imgResult)
        btnSaveResult = findViewById(R.id.btnSaveResult)
        resultVideoContainer = findViewById(R.id.resultVideoContainer)
        videoResult = findViewById(R.id.videoResult)
        btnSaveVideoResult = findViewById(R.id.btnSaveVideoResult)
        progressBar = findViewById(R.id.progressBar)
    }

    private fun bindActions() {
        baseUrlEdit.addTextChangedListener {
            if (suspendTextCallbacks) {
                return@addTextChangedListener
            }
            viewModel.onAction(MainUiAction.BaseUrlChanged(baseUrlEdit.text.toString()))
        }

        accessTokenEdit.addTextChangedListener {
            if (suspendTextCallbacks) {
                return@addTextChangedListener
            }
            viewModel.onAction(MainUiAction.TokenChanged(accessTokenEdit.text.toString()))
        }

        radioMode.setOnCheckedChangeListener { _, _ ->
            updateActionButtonForMode()
            val current = targetUri
            if (current != null && !viewModel.isTargetAllowed(current, isPhotoVideoMode())) {
                targetUri = null
                txtTarget.text = "Target: not selected"
                clearTargetPreview()
            }
        }

        btnTakeSelfie.setOnClickListener {
            showSourceActionsDialog()
        }

        btnPickTarget.setOnClickListener {
            showTargetActionsDialog()
        }

        btnStart.setOnClickListener {
            viewModel.onAction(
                MainUiAction.StartJob(
                    sourceUri = sourceUri,
                    targetUri = targetUri,
                    mode = currentMode(),
                    isPhotoVideoMode = isPhotoVideoMode(),
                ),
            )
        }

        btnCheckJob.setOnClickListener {
            viewModel.onAction(MainUiAction.CheckJobStatus(editJobId.text.toString()))
        }

        btnSaveResult.setOnClickListener {
            viewModel.onAction(MainUiAction.SaveResultImage)
        }

        btnSaveVideoResult.setOnClickListener {
            viewModel.onAction(MainUiAction.SaveResultVideo)
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        renderState(state)
                    }
                }
                launch {
                    viewModel.effect.collect { effect ->
                        when (effect) {
                            is MainUiEffect.Toast -> toast(effect.message)
                        }
                    }
                }
            }
        }
    }

    private fun renderState(state: MainUiState) {
        setTextIfChanged(baseUrlEdit, state.baseUrl)
        setTextIfChanged(accessTokenEdit, state.token)

        txtAuthStatus.text = state.authStatus
        txtStatus.text = state.statusText
        txtJobStatus.text = state.jobStatusText
        progressBar.progress = state.progress
        btnStart.isEnabled = state.isStartEnabled
        btnStart.text = state.startButtonText

        state.lastJobId?.let { lastJobId ->
            if (editJobId.text.toString().isBlank()) {
                setTextIfChanged(editJobId, lastJobId)
            }
        }

        txtHealth.text = when (state.healthOnline) {
            true -> "Health: online"
            false -> "Health: offline"
            null -> "Health: unknown"
        }

        renderResult(state)
    }

    private fun renderResult(state: MainUiState) {
        val imagePath = state.resultImagePath
        val videoPath = state.resultVideoPath

        if (imagePath != null) {
            if (renderedImagePath != imagePath) {
                imgResult.setImageURI(Uri.fromFile(File(imagePath)))
                renderedImagePath = imagePath
            }
            if (resultImageContainer.visibility != View.VISIBLE) {
                resultImageContainer.visibility = View.VISIBLE
            }
        } else {
            renderedImagePath = null
            imgResult.setImageDrawable(null)
            resultImageContainer.visibility = View.GONE
        }

        if (videoPath != null) {
            if (renderedVideoPath != videoPath) {
                videoResult.setVideoPath(videoPath)
                videoResult.start()
                renderedVideoPath = videoPath
            }
            if (resultVideoContainer.visibility != View.VISIBLE) {
                resultVideoContainer.visibility = View.VISIBLE
            }
        } else {
            renderedVideoPath = null
            videoResult.stopPlayback()
            resultVideoContainer.visibility = View.GONE
        }
    }

    private fun setTextIfChanged(editText: EditText, value: String) {
        if (editText.text.toString() == value) {
            return
        }
        suspendTextCallbacks = true
        editText.setText(value)
        editText.setSelection(value.length)
        suspendTextCallbacks = false
    }

    private fun showSourceActionsDialog() {
        val actions = arrayOf(
            "Take selfie (source)",
            "Pick source photo (gallery)",
            "Pick source photo (files)",
        )
        AlertDialog.Builder(this)
            .setTitle("Source actions")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> launchSelfieCapture()
                    1 -> pickSourceGalleryLauncher.launch(
                        PickVisualMediaRequest(PickVisualMedia.ImageOnly),
                    )
                    2 -> pickSourceFileLauncher.launch(arrayOf("image/*"))
                }
            }
            .show()
    }

    private fun showTargetActionsDialog() {
        val label = if (isPhotoVideoMode()) "video" else "photo"
        val actions = arrayOf(
            "Pick target $label (gallery)",
            "Pick target $label (files)",
        )
        AlertDialog.Builder(this)
            .setTitle("Target actions")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> launchTargetFromGallery()
                    1 -> launchTargetFromFiles()
                }
            }
            .show()
    }

    private fun launchSelfieCapture() {
        runCatching {
            val file = File.createTempFile("selfie_", ".jpg", cacheDir)
            FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        }.onSuccess { uri ->
            pendingSelfieUri = uri
            takeSelfieLauncher.launch(uri)
        }.onFailure {
            toast("Unable to open camera")
        }
    }

    private fun launchTargetFromGallery() {
        val request = if (isPhotoVideoMode()) {
            PickVisualMediaRequest(PickVisualMedia.VideoOnly)
        } else {
            PickVisualMediaRequest(PickVisualMedia.ImageOnly)
        }
        pickTargetGalleryLauncher.launch(request)
    }

    private fun launchTargetFromFiles() {
        val mimeTypes = if (isPhotoVideoMode()) {
            arrayOf("video/*", "video/mp4", "video/quicktime")
        } else {
            arrayOf("image/*")
        }
        pickTargetFileLauncher.launch(mimeTypes)
    }

    private fun handleSelectedSource(uri: Uri, label: String) {
        if (!viewModel.isImageUri(uri)) {
            toast("Source must be a photo (jpg/jpeg/png)")
            return
        }
        sourceUri = uri
        txtSource.text = label
        showSourcePreview(uri)
    }

    private fun handleSelectedTarget(uri: Uri, label: String) {
        val isPhotoVideo = isPhotoVideoMode()
        if (!viewModel.isTargetAllowed(uri, isPhotoVideo)) {
            toast("Target must be ${viewModel.targetErrorText(isPhotoVideo)}")
            return
        }
        targetUri = uri
        txtTarget.text = label
        showTargetPreview(uri)
    }

    private fun showSourcePreview(uri: Uri) {
        imgSourcePreview.setImageURI(uri)
        imgSourcePreview.visibility = View.VISIBLE
    }

    private fun showTargetPreview(uri: Uri) {
        if (viewModel.isVideoUri(uri)) {
            val frame: Bitmap? = viewModel.extractVideoPreviewFrame(uri)
            imgTargetPreview.setImageBitmap(frame)
        } else {
            imgTargetPreview.setImageURI(uri)
        }
        imgTargetPreview.visibility = View.VISIBLE
    }

    private fun clearTargetPreview() {
        imgTargetPreview.setImageDrawable(null)
        imgTargetPreview.visibility = View.GONE
    }

    private fun updateActionButtonForMode() {
        btnTakeSelfie.text = "Source actions"
        btnPickTarget.text = if (isPhotoVideoMode()) {
            "Target actions (video)"
        } else {
            "Target actions (photo)"
        }
    }

    private fun isPhotoVideoMode(): Boolean {
        val checkedId = radioMode.checkedRadioButtonId
        return checkedId == R.id.modePhotoVideoFast || checkedId == R.id.modePhotoVideoQuality
    }

    private fun currentMode(): String {
        return when (radioMode.checkedRadioButtonId) {
            R.id.modePhotoVideoQuality -> "photo_video_quality"
            R.id.modePhotoPhotoGpen -> "photo_photo_gpen"
            R.id.modePhotoPhotoCodeformer -> "photo_photo_codeformer"
            else -> "photo_video_fast"
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
