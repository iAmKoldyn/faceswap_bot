package com.facefusion.app.data.repository

import android.content.Context
import com.facefusion.app.data.network.FaceFusionApi
import com.facefusion.app.data.network.JobCreateRequest
import com.facefusion.app.data.network.JobResponse
import com.facefusion.app.data.network.NetworkClientFactory
import com.facefusion.app.data.network.SseClient
import com.facefusion.app.domain.result.AppResult
import com.facefusion.app.domain.result.ErrorType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import retrofit2.HttpException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketTimeoutException

class FaceFusionRepositoryImpl(
    context: Context,
    private val networkClientFactory: NetworkClientFactory = NetworkClientFactory(),
    private val sseClient: SseClient = SseClient(networkClientFactory),
) : FaceFusionRepository {
    private val appContext = context.applicationContext

    override suspend fun health(baseUrl: String): AppResult<Boolean> {
        return safeApiCall {
            api(baseUrl).health().status.equals("ok", ignoreCase = true)
        }
    }

    override suspend fun verify(baseUrl: String, token: String): AppResult<String> {
        return safeApiCall {
            api(baseUrl).verify(authHeader(token)).userId
        }
    }

    override suspend fun createJob(baseUrl: String, token: String, mode: String): AppResult<JobResponse> {
        return safeApiCall {
            api(baseUrl).createJob(JobCreateRequest(mode), authHeader(token))
        }
    }

    override suspend fun uploadSource(
        baseUrl: String,
        token: String,
        jobId: String,
        sourceFile: File,
        mime: String?,
    ): AppResult<JobResponse> {
        return safeApiCall {
            val part = asMultipart("file", sourceFile, mime)
            api(baseUrl).uploadSource(jobId, authHeader(token), part)
        }
    }

    override suspend fun uploadTarget(
        baseUrl: String,
        token: String,
        jobId: String,
        targetFile: File,
        mime: String?,
    ): AppResult<JobResponse> {
        return safeApiCall {
            val part = asMultipart("file", targetFile, mime)
            api(baseUrl).uploadTarget(jobId, authHeader(token), part)
        }
    }

    override suspend fun submit(baseUrl: String, token: String, jobId: String): AppResult<JobResponse> {
        return safeApiCall {
            api(baseUrl).submitJob(jobId, authHeader(token), emptyMap())
        }
    }

    override suspend fun getJob(baseUrl: String, token: String, jobId: String): AppResult<JobResponse> {
        return safeApiCall {
            api(baseUrl).getJob(jobId, authHeader(token))
        }
    }

    override fun observeJobEvents(baseUrl: String, token: String, jobId: String): Flow<AppResult<JobResponse>> {
        return sseClient.observeJobEvents(baseUrl, authHeader(token), jobId)
    }

    override suspend fun downloadResult(
        baseUrl: String,
        token: String,
        jobId: String,
        targetKind: String,
    ): AppResult<File> = withContext(Dispatchers.IO) {
        runCatching {
            val ext = if (targetKind == "video") ".mp4" else ".jpg"
            val outFile = File(appContext.cacheDir, "result_${jobId}$ext")
            api(baseUrl).getResult(jobId, authHeader(token)).use { body ->
                body.byteStream().use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            outFile
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = { errorResult(it) },
        )
    }

    private fun api(baseUrl: String): FaceFusionApi = networkClientFactory.createApi(baseUrl)

    private fun authHeader(token: String): String {
        val trimmed = token.trim()
        return if (trimmed.startsWith("Bearer ", ignoreCase = true)) trimmed else "Bearer $trimmed"
    }

    private fun asMultipart(field: String, file: File, mime: String?): MultipartBody.Part {
        val type = (mime ?: "application/octet-stream").toMediaTypeOrNull()
        val body = file.asRequestBody(type)
        return MultipartBody.Part.createFormData(field, file.name, body)
    }

    private suspend fun <T> safeApiCall(block: suspend () -> T): AppResult<T> {
        return try {
            AppResult.Success(block())
        } catch (t: Throwable) {
            errorResult(t)
        }
    }

    private fun errorResult(t: Throwable): AppResult.Error {
        return when (t) {
            is HttpException -> {
                when (t.code()) {
                    401, 403 -> AppResult.Error(ErrorType.AUTH, "Unauthorized", t)
                    408 -> AppResult.Error(ErrorType.TIMEOUT, t.message(), t)
                    in 400..499 -> AppResult.Error(ErrorType.VALIDATION, t.message(), t)
                    in 500..599 -> AppResult.Error(ErrorType.SERVER, t.message(), t)
                    else -> AppResult.Error(ErrorType.UNKNOWN, t.message, t)
                }
            }
            is SocketTimeoutException -> AppResult.Error(ErrorType.TIMEOUT, t.message, t)
            is IOException -> AppResult.Error(ErrorType.NETWORK, t.message, t)
            else -> AppResult.Error(ErrorType.UNKNOWN, t.message, t)
        }
    }
}
