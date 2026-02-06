package com.facefusion.app

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.view.View
import android.widget.*
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var baseUrlEdit: EditText
    private lateinit var accessTokenEdit: EditText
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
    private lateinit var editJobId: EditText
    private lateinit var imgSourcePreview: ImageView
    private lateinit var imgTargetPreview: ImageView
    private lateinit var resultImageContainer: FrameLayout
    private lateinit var btnSaveResult: ImageButton
    private lateinit var resultVideoContainer: FrameLayout
    private lateinit var btnSaveVideoResult: ImageButton
    private lateinit var progressBar: ProgressBar
    private lateinit var imgResult: ImageView
    private lateinit var videoResult: VideoView

    private var sourceUri: Uri? = null
    private var targetUri: Uri? = null
    private var pendingSelfieUri: Uri? = null
    private var jwtToken: String? = null
    private var authCheckJob: Job? = null
    private var healthMonitorJob: Job? = null
    private var sseCall: okhttp3.Call? = null
    private var sseObserverJob: Job? = null
    private var statusWatchdogJob: Job? = null
    private var activeJobId: String? = null
    private var isJobRunning: Boolean = false
    private var currentResultImageFile: File? = null
    private var currentResultVideoFile: File? = null
    private var downloadingJobId: String? = null
    private val gson = Gson()
    private var lastJobId: String? = null

    private val prefs by lazy {
        getSharedPreferences("facefusion_mobile_prefs", Context.MODE_PRIVATE)
    }

    private val pickSourceLauncher = registerForActivityResult(PickVisualMedia()) { uri ->
        uri?.let { handleSelectedSource(it, "Source: selected (gallery)") }
    }

    private val pickSourceFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { handleSelectedSource(it, "Source: selected (files)") }
    }

    private val pickTargetLauncher = registerForActivityResult(PickVisualMedia()) { uri ->
        uri?.let { handleSelectedTarget(it, "Target: selected (gallery)") }
    }

    private val pickTargetFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { handleSelectedTarget(it, "Target: selected (files)") }
    }

    private val takeSelfieLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            pendingSelfieUri?.let {
                sourceUri = it
                txtSource.text = "Source: selfie"
                showSourcePreview(it)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        baseUrlEdit = findViewById(R.id.editBaseUrl)
        accessTokenEdit = findViewById(R.id.editAccessToken)
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
        editJobId = findViewById(R.id.editJobId)
        imgSourcePreview = findViewById(R.id.imgSourcePreview)
        imgTargetPreview = findViewById(R.id.imgTargetPreview)
        resultImageContainer = findViewById(R.id.resultImageContainer)
        btnSaveResult = findViewById(R.id.btnSaveResult)
        resultVideoContainer = findViewById(R.id.resultVideoContainer)
        btnSaveVideoResult = findViewById(R.id.btnSaveVideoResult)
        progressBar = findViewById(R.id.progressBar)
        imgResult = findViewById(R.id.imgResult)
        videoResult = findViewById(R.id.videoResult)

        if (BuildConfig.DEFAULT_BASE_URL.isNotBlank()) {
            baseUrlEdit.setText(BuildConfig.DEFAULT_BASE_URL)
        }
        val savedBaseUrl = prefs.getString("base_url", "").orEmpty()
        if (savedBaseUrl.isNotBlank()) {
            baseUrlEdit.setText(savedBaseUrl)
        }
        val savedToken = prefs.getString("auth_token", "").orEmpty()
        if (savedToken.isNotBlank()) {
            accessTokenEdit.setText(savedToken)
            jwtToken = savedToken
            txtAuthStatus.text = "Auth: token restored"
        } else {
            txtAuthStatus.text = "Auth: not set"
        }
        updateActionButtonForMode()
        resetResultPreview()

        accessTokenEdit.addTextChangedListener {
            prefs.edit().putString("auth_token", accessTokenEdit.text.toString().trim()).apply()
            scheduleAuthCheck()
        }
        baseUrlEdit.addTextChangedListener {
            prefs.edit().putString("base_url", baseUrlEdit.text.toString().trim()).apply()
            startHealthMonitor()
            scheduleAuthCheck()
        }

        btnTakeSelfie.setOnClickListener {
            showSourceActionsDialog()
        }

        radioMode.setOnCheckedChangeListener { _, _ ->
            updateActionButtonForMode()
            targetUri?.let {
                if (!isTargetAllowed(it)) {
                    targetUri = null
                    txtTarget.text = "Target: not selected"
                    clearTargetPreview()
                }
            }
        }

        btnPickTarget.setOnClickListener {
            showTargetActionsDialog()
        }

        btnSaveResult.setOnClickListener {
            saveResultImageToGallery()
        }
        btnSaveVideoResult.setOnClickListener {
            saveResultVideoToGallery()
        }

        btnStart.setOnClickListener {
            startJob()
        }

        btnCheckJob.setOnClickListener {
            checkJobStatus()
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

    private fun updateActionButtonForMode() {
        btnTakeSelfie.text = "Source actions"
        btnPickTarget.text = if (isPhotoVideoMode()) {
            "Target actions (video)"
        } else {
            "Target actions (photo)"
        }
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
                    1 -> launchSourcePicker()
                    2 -> launchSourceFilePicker()
                }
            }
            .show()
    }

    private fun showTargetActionsDialog() {
        val targetLabel = if (isPhotoVideoMode()) "video" else "photo"
        val actions = arrayOf(
            "Pick target $targetLabel (gallery)",
            "Pick target $targetLabel (files)",
        )
        AlertDialog.Builder(this)
            .setTitle("Target actions")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> launchTargetPicker()
                    1 -> launchTargetFilePicker()
                }
            }
            .show()
    }

    private fun launchSourcePicker() {
        pickSourceLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun launchSourceFilePicker() {
        pickSourceFileLauncher.launch(arrayOf("image/*"))
    }

    private fun launchTargetPicker() {
        val request = if (isPhotoVideoMode()) {
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
        } else {
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        }
        pickTargetLauncher.launch(request)
    }

    private fun launchTargetFilePicker() {
        val mimeTypes = if (isPhotoVideoMode()) {
            arrayOf("video/*", "video/mp4", "video/quicktime")
        } else {
            arrayOf("image/*")
        }
        pickTargetFileLauncher.launch(mimeTypes)
    }

    private fun handleSelectedSource(uri: Uri, label: String) {
        if (!isImageUri(uri)) {
            toast("Source must be a photo (jpg/jpeg/png)")
            return
        }
        sourceUri = uri
        txtSource.text = label
        showSourcePreview(uri)
    }

    private fun handleSelectedTarget(uri: Uri, label: String) {
        if (!isTargetAllowed(uri)) {
            val expected = if (isPhotoVideoMode()) "video (mp4/mov)" else "photo (jpg/jpeg/png)"
            toast("Target must be $expected")
            return
        }
        targetUri = uri
        txtTarget.text = label
        showTargetPreview(uri)
    }

    private fun showSourcePreview(uri: Uri) {
        imgSourcePreview.visibility = View.VISIBLE
        imgSourcePreview.setImageURI(uri)
    }

    private fun showTargetPreview(uri: Uri) {
        imgTargetPreview.visibility = View.VISIBLE
        if (isVideoUri(uri)) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(this, uri)
                val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (frame != null) {
                    imgTargetPreview.setImageBitmap(frame)
                } else {
                    imgTargetPreview.setImageDrawable(null)
                }
            } catch (_: Exception) {
                imgTargetPreview.setImageDrawable(null)
            } finally {
                retriever.release()
            }
            return
        }
        imgTargetPreview.setImageURI(uri)
    }

    private fun clearTargetPreview() {
        imgTargetPreview.setImageDrawable(null)
        imgTargetPreview.visibility = View.GONE
    }

    private fun resetResultPreview() {
        imgResult.setImageDrawable(null)
        resultImageContainer.visibility = View.GONE
        btnSaveResult.visibility = View.GONE
        currentResultImageFile = null
        resultVideoContainer.visibility = View.GONE
        btnSaveVideoResult.visibility = View.GONE
        currentResultVideoFile = null
        videoResult.stopPlayback()
        videoResult.visibility = View.GONE
    }

    private fun launchSelfieCapture() {
        try {
            val file = File.createTempFile("selfie_", ".jpg", cacheDir)
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            pendingSelfieUri = uri
            takeSelfieLauncher.launch(uri)
        } catch (e: Exception) {
            toast("Unable to open camera")
        }
    }

    private fun isTargetAllowed(uri: Uri): Boolean {
        return if (isPhotoVideoMode()) isVideoUri(uri) else isImageUri(uri)
    }

    private fun isImageUri(uri: Uri): Boolean {
        val mime = contentResolver.getType(uri)?.lowercase()
        if (mime != null) {
            return mime == "image/jpeg" || mime == "image/jpg" || mime == "image/png"
        }
        val ext = extensionFromName(getDisplayName(uri))
        return ext == ".jpg" || ext == ".jpeg" || ext == ".png"
    }

    private fun isVideoUri(uri: Uri): Boolean {
        val mime = contentResolver.getType(uri)?.lowercase()
        if (mime != null) {
            return mime == "video/mp4" || mime == "video/quicktime"
        }
        val ext = extensionFromName(getDisplayName(uri))
        return ext == ".mp4" || ext == ".mov"
    }

    private fun createApi(baseUrl: String): FaceFusionApi {
        val url = baseUrl.trim().removeSuffix("/") + "/"
        val logger = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .addInterceptor(logger)
            .build()

        return Retrofit.Builder()
            .baseUrl(url)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(FaceFusionApi::class.java)
    }

    private fun startJob() {
        if (isJobRunning) {
            toast("Wait until current job is finished")
            return
        }
        val source = sourceUri
        val target = targetUri
        if (source == null || target == null) {
            toast("Select source and target")
            return
        }
        if (!isImageUri(source)) {
            toast("Source must be a photo (jpg/jpeg/png)")
            return
        }
        if (isPhotoVideoMode() && !isVideoUri(target)) {
            toast("Target must be a video (mp4/mov)")
            return
        }
        if (!isPhotoVideoMode() && !isImageUri(target)) {
            toast("Target must be a photo (jpg/jpeg/png)")
            return
        }

        val baseUrl = baseUrlEdit.text.toString().trim()
        if (baseUrl.isEmpty()) {
            toast("Base URL is empty")
            return
        }

        sseObserverJob?.cancel()
        sseCall?.cancel()
        statusWatchdogJob?.cancel()
        activeJobId = null
        downloadingJobId = null
        setJobRunningState(true)
        resetResultPreview()
        progressBar.progress = 0
        txtStatus.text = "Status: starting"

        lifecycleScope.launch {
            try {
                val api = createApi(baseUrl)
                val token = resolveToken()
                if (token == null) {
                    setJobRunningState(false)
                    return@launch
                }
                val auth = normalizeBearer(token)

                txtStatus.text = "Status: creating job"
                val job = api.createJob(JobCreateRequest(currentMode()), auth)
                txtStatus.text = "Status: created ${job.jobId}"
                activeJobId = job.jobId
                lastJobId = job.jobId
                editJobId.setText(job.jobId)

                txtStatus.text = "Status: uploading source"
                val sourceMime = contentResolver.getType(source)
                val sourceFile = copyToCache(source, "source", sourceMime)
                val sourcePart = filePart("file", sourceFile, sourceMime)
                api.uploadSource(job.jobId, auth, sourcePart)

                txtStatus.text = "Status: uploading target"
                val targetMime = contentResolver.getType(target)
                val targetFile = copyToCache(target, "target", targetMime)
                val targetPart = filePart("file", targetFile, targetMime)
                api.uploadTarget(job.jobId, auth, targetPart)

                txtStatus.text = "Status: submitting"
                api.submitJob(job.jobId, auth, emptyMap())

                startStatusWatchdog(baseUrl, auth, job.jobId)
                listenJobEvents(baseUrl, auth, job.jobId)
            } catch (e: Exception) {
                if (e is HttpException && e.code() == 401) {
                    txtAuthStatus.text = "Auth: unauthorized"
                    txtStatus.text = "Error: unauthorized"
                } else {
                    txtStatus.text = "Error: ${e.message}"
                }
                setJobRunningState(false)
            }
        }
    }

    private fun listenJobEvents(baseUrl: String, auth: String, jobId: String) {
        sseObserverJob?.cancel()
        sseCall?.cancel()
        sseObserverJob = lifecycleScope.launch(Dispatchers.IO) {
            var attempt = 0
            while (activeJobId == jobId) {
                val url = baseUrl.trim().removeSuffix("/") + "/jobs/$jobId/events"
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", auth)
                    .addHeader("Accept", "text/event-stream")
                    .build()

                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .retryOnConnectionFailure(true)
                    .build()

                try {
                    sseCall = client.newCall(request)
                    val response = sseCall?.execute() ?: break
                    if (!response.isSuccessful) {
                        postStatus("Error: SSE ${response.code}")
                        response.close()
                        delay(1500)
                        continue
                    }
                    if (attempt > 0) {
                        postStatus("Status: SSE reconnected")
                    }
                    val completed = streamSse(response, baseUrl, auth, jobId)
                    if (completed) {
                        break
                    }
                    if (reconcileJobStatus(baseUrl, auth, jobId)) {
                        break
                    }
                } catch (_: IOException) {
                    if (activeJobId == jobId) {
                        postStatus("Status: SSE reconnecting...")
                    }
                    if (reconcileJobStatus(baseUrl, auth, jobId)) {
                        break
                    }
                }
                attempt += 1
                delay(1500)
            }
        }
    }

    private fun startStatusWatchdog(baseUrl: String, auth: String, jobId: String) {
        statusWatchdogJob?.cancel()
        statusWatchdogJob = lifecycleScope.launch {
            while (isActive && activeJobId == jobId) {
                try {
                    val api = createApi(baseUrl)
                    val job = api.getJob(jobId, auth)
                    val formatted = formatStatus(job.status, job.stage)
                    txtJobStatus.text = "Job: $formatted (${job.progress}%)"
                    if (job.status == "completed") {
                        if (activeJobId == jobId) {
                            activeJobId = null
                            txtStatus.text = "Status: completed, downloading..."
                            progressBar.progress = 100
                            launchResultDownload(baseUrl, auth, jobId, job.targetKind)
                        }
                        break
                    }
                    if (job.status == "failed" || job.status == "cancelled") {
                        if (activeJobId == jobId) {
                            activeJobId = null
                            txtStatus.text = "Status: ${job.status}"
                            setJobRunningState(false)
                        }
                        break
                    }
                } catch (_: Exception) {
                    // Ignore transient network issues; SSE may still deliver updates.
                }
                delay(2000)
            }
        }
    }

    private fun streamSse(response: Response, baseUrl: String, auth: String, jobId: String): Boolean {
        var completed = false
        response.body?.use { body ->
            val source = body.source()
            while (!source.exhausted() && activeJobId == jobId) {
                val line = source.readUtf8Line() ?: continue
                if (line.isBlank()) continue
                if (line.startsWith("data:")) {
                    val data = line.removePrefix("data:").trim()
                    if (handleJobEvent(data, baseUrl, auth, jobId)) {
                        completed = true
                        break
                    }
                }
            }
        }
        return completed
    }

    private fun handleJobEvent(data: String, baseUrl: String, auth: String, jobId: String): Boolean {
        val job = runCatching { gson.fromJson(data, JobResponse::class.java) }.getOrNull() ?: return false
        if (job.jobId != jobId || activeJobId != jobId) {
            return false
        }
        runOnUiThread {
            txtStatus.text = "Status: ${formatStatus(job.status, job.stage)}"
            progressBar.progress = job.progress
        }
        if (job.status == "completed") {
            activeJobId = null
            sseCall?.cancel()
            runOnUiThread {
                txtStatus.text = "Status: completed, downloading..."
                progressBar.progress = 100
            }
            launchResultDownload(baseUrl, auth, jobId, job.targetKind)
            return true
        } else if (job.status == "failed" || job.status == "cancelled") {
            activeJobId = null
            sseCall?.cancel()
            runOnUiThread {
                txtStatus.text = "Status: ${job.status}"
            }
            setJobRunningState(false)
            return true
        }
        return false
    }

    private suspend fun reconcileJobStatus(baseUrl: String, auth: String, jobId: String): Boolean {
        if (activeJobId != jobId) {
            return true
        }
        return try {
            val api = createApi(baseUrl)
            val job = api.getJob(jobId, auth)
            if (job.jobId != jobId || activeJobId != jobId) {
                return true
            }
            runOnUiThread {
                txtStatus.text = "Status: ${formatStatus(job.status, job.stage)}"
                progressBar.progress = job.progress
            }
            when (job.status) {
                "completed" -> {
                    activeJobId = null
                    runOnUiThread {
                        txtStatus.text = "Status: completed, downloading..."
                        progressBar.progress = 100
                    }
                    launchResultDownload(baseUrl, auth, jobId, job.targetKind)
                    true
                }
                "failed", "cancelled" -> {
                    activeJobId = null
                    runOnUiThread {
                        txtStatus.text = "Status: ${job.status}"
                    }
                    setJobRunningState(false)
                    true
                }
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun launchResultDownload(baseUrl: String, auth: String, jobId: String, targetKind: String) {
        if (downloadingJobId == jobId) {
            return
        }
        downloadingJobId = jobId
        lifecycleScope.launch {
            try {
                val api = createApi(baseUrl)
                downloadResult(api, auth, jobId, targetKind)
            } catch (e: Exception) {
                txtStatus.text = "Error: ${e.message ?: "download failed"}"
            } finally {
                downloadingJobId = null
                setJobRunningState(false)
            }
        }
    }

    private fun postStatus(message: String) {
        runOnUiThread {
            txtStatus.text = message
        }
    }

    private fun resolveToken(): String? {
        val manualToken = accessTokenEdit.text.toString().trim()
        if (manualToken.isNotEmpty()) {
            val token = stripBearer(manualToken)
            jwtToken = token
            txtAuthStatus.text = "Auth: token set"
            return token
        }
        val cached = jwtToken
        if (!cached.isNullOrBlank()) {
            txtAuthStatus.text = "Auth: using cached token"
            return cached
        }

        txtAuthStatus.text = "Auth: missing token"
        toast("Paste access token")
        return null
    }

    private fun stripBearer(token: String): String {
        val trimmed = token.trim()
        return if (trimmed.startsWith("Bearer ", ignoreCase = true)) {
            trimmed.substring(7).trim()
        } else {
            trimmed
        }
    }

    private fun normalizeBearer(token: String): String {
        val trimmed = token.trim()
        return if (trimmed.startsWith("Bearer ", ignoreCase = true)) trimmed else "Bearer $trimmed"
    }

    private fun scheduleAuthCheck() {
        authCheckJob?.cancel()
        val rawToken = accessTokenEdit.text.toString().trim()
        if (rawToken.isEmpty()) {
            txtAuthStatus.text = "Auth: not set"
            return
        }
        val baseUrl = baseUrlEdit.text.toString().trim()
        if (baseUrl.isEmpty()) {
            txtAuthStatus.text = "Auth: base URL missing"
            return
        }
        authCheckJob = lifecycleScope.launch {
            delay(400)
            try {
                val api = createApi(baseUrl)
                val token = stripBearer(rawToken)
                val auth = normalizeBearer(token)
                val resp = api.verify(auth)
                jwtToken = token
                txtAuthStatus.text = "Auth: ok (${resp.userId})"
            } catch (e: Exception) {
                if (e is HttpException && e.code() == 401) {
                    txtAuthStatus.text = "Auth: unauthorized"
                } else {
                    txtAuthStatus.text = "Auth: error"
                }
            }
        }
    }

    private fun checkJobStatus() {
        val baseUrl = baseUrlEdit.text.toString().trim()
        if (baseUrl.isEmpty()) {
            toast("Base URL is empty")
            return
        }
        val jobId = editJobId.text.toString().trim().ifEmpty { lastJobId.orEmpty() }
        if (jobId.isEmpty()) {
            toast("Job ID is empty")
            return
        }
        val token = resolveToken() ?: return
        val auth = normalizeBearer(token)
        txtJobStatus.text = "Job: checking"
        lifecycleScope.launch {
            try {
                val api = createApi(baseUrl)
                val job = api.getJob(jobId, auth)
                val formatted = formatStatus(job.status, job.stage)
                txtJobStatus.text = "Job: $formatted (${job.progress}%)"
                txtStatus.text = "Status: $formatted"
                progressBar.progress = job.progress
                if (job.status == "completed" || job.status == "failed" || job.status == "cancelled") {
                    if (activeJobId == job.jobId) {
                        activeJobId = null
                    }
                    setJobRunningState(false)
                }
            } catch (e: Exception) {
                if (e is HttpException && e.code() == 401) {
                    txtAuthStatus.text = "Auth: unauthorized"
                    txtJobStatus.text = "Job: unauthorized"
                } else {
                    txtJobStatus.text = "Job: error"
                }
            }
        }
    }

    private suspend fun downloadResult(api: FaceFusionApi, auth: String, jobId: String, targetKind: String) {
        val ext = if (targetKind == "video") ".mp4" else ".jpg"
        val outFile = File(cacheDir, "result_${jobId}$ext")
        api.getResult(jobId, auth).use { response ->
            response.byteStream().use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
        if (targetKind == "video") {
            currentResultVideoFile = outFile
            resultVideoContainer.visibility = View.VISIBLE
            btnSaveVideoResult.visibility = View.VISIBLE
            resultImageContainer.visibility = View.GONE
            videoResult.visibility = VideoView.VISIBLE
            videoResult.setVideoPath(outFile.absolutePath)
            videoResult.start()
        } else {
            currentResultImageFile = outFile
            resultImageContainer.visibility = View.VISIBLE
            btnSaveResult.visibility = View.VISIBLE
            resultVideoContainer.visibility = View.GONE
            btnSaveVideoResult.visibility = View.GONE
            currentResultVideoFile = null
            videoResult.visibility = View.GONE
            imgResult.setImageURI(Uri.fromFile(outFile))
        }
    }

    private suspend fun updateHealthIndicator(baseUrl: String) {
        val healthy = try {
            val api = createApi(baseUrl)
            api.health().status.equals("ok", ignoreCase = true)
        } catch (_: Exception) {
            false
        }
        runOnUiThread {
            if (healthy) {
                txtHealth.text = "Health: \u2714 online"
                txtHealth.setTextColor(Color.parseColor("#2E7D32"))
            } else {
                txtHealth.text = "Health: \u2716 offline"
                txtHealth.setTextColor(Color.parseColor("#C62828"))
            }
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

    private fun startHealthMonitor() {
        healthMonitorJob?.cancel()
        val baseUrl = baseUrlEdit.text.toString().trim()
        if (baseUrl.isEmpty()) {
            txtHealth.text = "Health: base URL missing"
            txtHealth.setTextColor(Color.parseColor("#C62828"))
            return
        }
        healthMonitorJob = lifecycleScope.launch {
            while (isActive) {
                updateHealthIndicator(baseUrl)
                delay(10000)
            }
        }
    }

    private fun saveResultImageToGallery() {
        val imageFile = currentResultImageFile
        if (imageFile == null || !imageFile.exists()) {
            toast("No image result to save")
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val saved = runCatching {
                val mime = when (imageFile.extension.lowercase()) {
                    "png" -> "image/png"
                    else -> "image/jpeg"
                }
                val filename = "faceswap_${System.currentTimeMillis()}.${imageFile.extension.ifBlank { "jpg" }}"
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, mime)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FaceFusion")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }
                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("Unable to create gallery item")
                resolver.openOutputStream(uri)?.use { output ->
                    imageFile.inputStream().use { input ->
                        input.copyTo(output)
                    }
                } ?: throw IOException("Unable to open gallery stream")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val done = ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    }
                    resolver.update(uri, done, null, null)
                }
                uri
            }.isSuccess
            runOnUiThread {
                if (saved) {
                    toast("Saved to gallery")
                } else {
                    toast("Failed to save image")
                }
            }
        }
    }

    private fun saveResultVideoToGallery() {
        val videoFile = currentResultVideoFile
        if (videoFile == null || !videoFile.exists()) {
            toast("No video result to save")
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val saved = runCatching {
                val mime = when (videoFile.extension.lowercase()) {
                    "mov" -> "video/quicktime"
                    else -> "video/mp4"
                }
                val ext = videoFile.extension.ifBlank { "mp4" }
                val filename = "faceswap_${System.currentTimeMillis()}.$ext"
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Video.Media.MIME_TYPE, mime)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/FaceFusion")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                }
                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("Unable to create gallery item")
                resolver.openOutputStream(uri)?.use { output ->
                    videoFile.inputStream().use { input ->
                        input.copyTo(output)
                    }
                } ?: throw IOException("Unable to open gallery stream")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val done = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }
                    resolver.update(uri, done, null, null)
                }
                uri
            }.isSuccess
            runOnUiThread {
                if (saved) {
                    toast("Video saved to gallery")
                } else {
                    toast("Failed to save video")
                }
            }
        }
    }

    private fun copyToCache(uri: Uri, prefix: String, mime: String?): File {
        val ext = extensionFromName(getDisplayName(uri)) ?: extensionFromMime(mime) ?: ""
        val name = if (ext.isNotEmpty()) {
            "${prefix}_${System.currentTimeMillis()}$ext"
        } else {
            "${prefix}_${System.currentTimeMillis()}"
        }
        val file = File(cacheDir, name)
        val input = contentResolver.openInputStream(uri) ?: throw IllegalStateException("Cannot open file")
        input.use { stream ->
            FileOutputStream(file).use { output ->
                stream.copyTo(output)
            }
        }
        return file
    }

    private fun getDisplayName(uri: Uri): String? {
        val cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) {
                    return it.getString(index)
                }
            }
        }
        return null
    }

    private fun extensionFromName(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val dot = name.lastIndexOf('.')
        if (dot == -1 || dot == name.length - 1) return null
        return name.substring(dot).lowercase()
    }

    private fun extensionFromMime(mime: String?): String? {
        return when (mime?.lowercase()) {
            "image/jpeg", "image/jpg" -> ".jpg"
            "image/png" -> ".png"
            "video/mp4" -> ".mp4"
            "video/quicktime" -> ".mov"
            else -> null
        }
    }

    private fun filePart(field: String, file: File, mime: String?): MultipartBody.Part {
        val type = (mime ?: "application/octet-stream").toMediaTypeOrNull()
        val body = file.asRequestBody(type)
        return MultipartBody.Part.createFormData(field, file.name, body)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun setJobRunningState(running: Boolean) {
        isJobRunning = running
        runOnUiThread {
            btnStart.isEnabled = !running
            btnStart.text = if (running) "Processing..." else "Start job"
        }
    }

    override fun onStart() {
        super.onStart()
        startHealthMonitor()
    }

    override fun onStop() {
        healthMonitorJob?.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        setJobRunningState(false)
        activeJobId = null
        downloadingJobId = null
        sseCall?.cancel()
        sseObserverJob?.cancel()
        statusWatchdogJob?.cancel()
        authCheckJob?.cancel()
        healthMonitorJob?.cancel()
        super.onDestroy()
    }
}

interface FaceFusionApi {
    @GET("health")
    suspend fun health(): HealthResponse

    @GET("auth/verify")
    suspend fun verify(
        @Header("Authorization") auth: String,
    ): AuthVerifyResponse

    @POST("jobs")
    suspend fun createJob(
        @Body body: JobCreateRequest,
        @Header("Authorization") auth: String,
    ): JobResponse

    @Multipart
    @POST("jobs/{jobId}/source")
    suspend fun uploadSource(
        @Path("jobId") jobId: String,
        @Header("Authorization") auth: String,
        @Part file: MultipartBody.Part,
    ): JobResponse

    @Multipart
    @POST("jobs/{jobId}/target")
    suspend fun uploadTarget(
        @Path("jobId") jobId: String,
        @Header("Authorization") auth: String,
        @Part file: MultipartBody.Part,
    ): JobResponse

    @FormUrlEncoded
    @POST("jobs/{jobId}/submit")
    suspend fun submitJob(
        @Path("jobId") jobId: String,
        @Header("Authorization") auth: String,
        @FieldMap fields: Map<String, String>,
    ): JobResponse

    @GET("jobs/{jobId}")
    suspend fun getJob(
        @Path("jobId") jobId: String,
        @Header("Authorization") auth: String,
    ): JobResponse

    @GET("jobs/{jobId}/result")
    suspend fun getResult(
        @Path("jobId") jobId: String,
        @Header("Authorization") auth: String,
    ): ResponseBody
}

data class JobCreateRequest(@SerializedName("mode") val mode: String)

data class HealthResponse(@SerializedName("status") val status: String)

data class AuthVerifyResponse(@SerializedName("user_id") val userId: String)

data class JobResponse(
    @SerializedName("job_id") val jobId: String,
    @SerializedName("status") val status: String,
    @SerializedName("mode") val mode: String,
    @SerializedName("target_kind") val targetKind: String,
    @SerializedName("owner_id") val ownerId: String,
    @SerializedName("progress") val progress: Int,
    @SerializedName("stage") val stage: String?,
)
