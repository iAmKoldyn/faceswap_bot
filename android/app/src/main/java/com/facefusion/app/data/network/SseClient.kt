package com.facefusion.app.data.network

import com.facefusion.app.domain.result.AppResult
import com.facefusion.app.domain.result.ErrorType
import com.google.gson.Gson
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.Request
import java.io.IOException

class SseClient(
    private val networkClientFactory: NetworkClientFactory,
    private val gson: Gson = Gson(),
) {
    fun observeJobEvents(
        baseUrl: String,
        authHeader: String,
        jobId: String,
    ): Flow<AppResult<JobResponse>> = flow {
        var reconnectAttempt = 0
        while (currentCoroutineContext().isActive) {
            val request = Request.Builder()
                .url(baseUrl.trim().removeSuffix("/") + "/jobs/$jobId/events")
                .addHeader("Authorization", authHeader)
                .addHeader("Accept", "text/event-stream")
                .build()

            val client = networkClientFactory.createSseHttpClient()
            val call = client.newCall(request)
            try {
                val response = call.execute()
                if (!response.isSuccessful) {
                    emit(
                        AppResult.Error(
                            type = ErrorType.SERVER,
                            message = "SSE HTTP ${response.code}",
                        ),
                    )
                    response.close()
                    delay(1500)
                    reconnectAttempt += 1
                    continue
                }

                response.body?.use { body ->
                    val source = body.source()
                    while (!source.exhausted() && currentCoroutineContext().isActive) {
                        val line = source.readUtf8Line() ?: continue
                        if (line.isBlank()) {
                            continue
                        }
                        if (line.startsWith("data:")) {
                            val payload = line.removePrefix("data:").trim()
                            val event = runCatching { gson.fromJson(payload, JobResponse::class.java) }
                                .getOrNull()
                            if (event != null) {
                                emit(AppResult.Success(event))
                                if (event.status in TERMINAL_STATUSES) {
                                    return@flow
                                }
                            }
                        }
                    }
                }
            } catch (io: IOException) {
                emit(
                    AppResult.Error(
                        type = ErrorType.NETWORK,
                        message = if (reconnectAttempt == 0) "SSE disconnected" else "SSE reconnecting",
                        cause = io,
                    ),
                )
            } catch (t: Throwable) {
                emit(AppResult.Error(type = ErrorType.UNKNOWN, message = t.message, cause = t))
            } finally {
                call.cancel()
            }
            reconnectAttempt += 1
            delay(1500)
        }
    }

    private companion object {
        val TERMINAL_STATUSES = setOf("completed", "failed", "cancelled")
    }
}
