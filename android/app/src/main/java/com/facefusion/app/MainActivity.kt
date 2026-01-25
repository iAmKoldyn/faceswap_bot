package com.facefusion.app

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
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
    private lateinit var btnPickSource: Button
    private lateinit var btnPickTarget: Button
    private lateinit var btnStart: Button
    private lateinit var btnHealth: Button
    private lateinit var btnCheckJob: Button
    private lateinit var txtSource: TextView
    private lateinit var txtTarget: TextView
    private lateinit var txtStatus: TextView
    private lateinit var txtAuthStatus: TextView
    private lateinit var txtHealth: TextView
    private lateinit var txtJobStatus: TextView
    private lateinit var editJobId: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var imgResult: ImageView
    private lateinit var videoResult: VideoView

    private var sourceUri: Uri? = null
    private var targetUri: Uri? = null
    private var jwtToken: String? = null
    private var sseCall: okhttp3.Call? = null
    private val gson = Gson()
    private var lastJobId: String? = null

    private val pickSourceLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            sourceUri = it
            txtSource.text = "Source: selected"
        }
    }

    private val pickTargetLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            targetUri = it
            txtTarget.text = "Target: selected"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        baseUrlEdit = findViewById(R.id.editBaseUrl)
        accessTokenEdit = findViewById(R.id.editAccessToken)
        radioMode = findViewById(R.id.radioMode)
        btnPickSource = findViewById(R.id.btnPickSource)
        btnPickTarget = findViewById(R.id.btnPickTarget)
        btnStart = findViewById(R.id.btnStart)
        btnHealth = findViewById(R.id.btnHealth)
        btnCheckJob = findViewById(R.id.btnCheckJob)
        txtSource = findViewById(R.id.txtSource)
        txtTarget = findViewById(R.id.txtTarget)
        txtStatus = findViewById(R.id.txtStatus)
        txtAuthStatus = findViewById(R.id.txtAuthStatus)
        txtHealth = findViewById(R.id.txtHealth)
        txtJobStatus = findViewById(R.id.txtJobStatus)
        editJobId = findViewById(R.id.editJobId)
        progressBar = findViewById(R.id.progressBar)
        imgResult = findViewById(R.id.imgResult)
        videoResult = findViewById(R.id.videoResult)

        if (BuildConfig.DEFAULT_BASE_URL.isNotBlank()) {
            baseUrlEdit.setText(BuildConfig.DEFAULT_BASE_URL)
        }
        txtAuthStatus.text = "Auth: not set"
        btnPickTarget.text = if (isPhotoVideoMode()) "Pick target video" else "Pick target photo"

        btnPickSource.setOnClickListener {
            pickSourceLauncher.launch(arrayOf("image/*"))
        }

        btnPickTarget.setOnClickListener {
            val mimeTypes = if (isPhotoVideoMode()) arrayOf("video/*") else arrayOf("image/*")
            pickTargetLauncher.launch(mimeTypes)
        }

        radioMode.setOnCheckedChangeListener { _, _ ->
            btnPickTarget.text = if (isPhotoVideoMode()) "Pick target video" else "Pick target photo"
        }

        btnStart.setOnClickListener {
            startJob()
        }

        btnHealth.setOnClickListener {
            healthCheck()
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
        val source = sourceUri
        val target = targetUri
        if (source == null || target == null) {
            toast("Select source and target")
            return
        }

        val baseUrl = baseUrlEdit.text.toString().trim()
        if (baseUrl.isEmpty()) {
            toast("Base URL is empty")
            return
        }

        imgResult.visibility = ImageView.GONE
        videoResult.visibility = VideoView.GONE
        progressBar.progress = 0
        txtStatus.text = "Status: starting"

        lifecycleScope.launch {
            try {
                val api = createApi(baseUrl)
                val token = resolveToken() ?: return@launch
                val auth = normalizeBearer(token)

                txtStatus.text = "Status: creating job"
                val job = api.createJob(JobCreateRequest(currentMode()), auth)
                txtStatus.text = "Status: created ${job.jobId}"
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

                listenJobEvents(baseUrl, auth, job.jobId)
            } catch (e: Exception) {
                if (e is HttpException && e.code() == 401) {
                    txtAuthStatus.text = "Auth: unauthorized"
                    txtStatus.text = "Error: unauthorized"
                } else {
                    txtStatus.text = "Error: ${e.message}"
                }
            }
        }
    }

    private fun listenJobEvents(baseUrl: String, auth: String, jobId: String) {
        sseCall?.cancel()
        val url = baseUrl.trim().removeSuffix("/") + "/jobs/$jobId/events"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", auth)
            .addHeader("Accept", "text/event-stream")
            .build()

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        sseCall = client.newCall(request)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = sseCall?.execute() ?: return@launch
                if (!response.isSuccessful) {
                    postStatus("Error: SSE ${response.code}")
                    response.close()
                    return@launch
                }
                streamSse(response, baseUrl, auth, jobId)
            } catch (e: IOException) {
                postStatus("Error: SSE connection failed")
            }
        }
    }

    private fun streamSse(response: Response, baseUrl: String, auth: String, jobId: String) {
        response.body?.use { body ->
            val source = body.source()
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: continue
                if (line.isBlank()) continue
                if (line.startsWith("data:")) {
                    val data = line.removePrefix("data:").trim()
                    handleJobEvent(data, baseUrl, auth, jobId)
                }
            }
        }
    }

    private fun handleJobEvent(data: String, baseUrl: String, auth: String, jobId: String) {
        val job = runCatching { gson.fromJson(data, JobResponse::class.java) }.getOrNull() ?: return
        runOnUiThread {
            val stage = job.stage ?: ""
            txtStatus.text = "Status: ${job.status} $stage"
            progressBar.progress = job.progress
        }
        if (job.status == "completed") {
            sseCall?.cancel()
            lifecycleScope.launch {
                val api = createApi(baseUrl)
                downloadResult(api, auth, jobId, job.targetKind)
            }
        } else if (job.status == "failed" || job.status == "cancelled") {
            sseCall?.cancel()
            runOnUiThread { txtStatus.text = "Status: ${job.status}" }
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

    private fun healthCheck() {
        val baseUrl = baseUrlEdit.text.toString().trim()
        if (baseUrl.isEmpty()) {
            toast("Base URL is empty")
            return
        }
        txtHealth.text = "Health: checking"
        lifecycleScope.launch {
            try {
                val api = createApi(baseUrl)
                val health = api.health()
                txtHealth.text = "Health: ${health.status}"
            } catch (e: Exception) {
                txtHealth.text = "Health: error"
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
                val stage = job.stage ?: ""
                txtJobStatus.text = "Job: ${job.status} $stage (${job.progress}%)"
                txtStatus.text = "Status: ${job.status} $stage"
                progressBar.progress = job.progress
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
        val outFile = File(cacheDir, "result$ext")
        api.getResult(jobId, auth).use { response ->
            response.byteStream().use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
        if (targetKind == "video") {
            videoResult.visibility = VideoView.VISIBLE
            imgResult.visibility = ImageView.GONE
            videoResult.setVideoPath(outFile.absolutePath)
            videoResult.start()
        } else {
            imgResult.visibility = ImageView.VISIBLE
            videoResult.visibility = VideoView.GONE
            imgResult.setImageURI(Uri.fromFile(outFile))
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

    override fun onDestroy() {
        sseCall?.cancel()
        super.onDestroy()
    }
}

interface FaceFusionApi {
    @GET("health")
    suspend fun health(): HealthResponse

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

data class JobResponse(
    @SerializedName("job_id") val jobId: String,
    @SerializedName("status") val status: String,
    @SerializedName("mode") val mode: String,
    @SerializedName("target_kind") val targetKind: String,
    @SerializedName("owner_id") val ownerId: String,
    @SerializedName("progress") val progress: Int,
    @SerializedName("stage") val stage: String?,
)
