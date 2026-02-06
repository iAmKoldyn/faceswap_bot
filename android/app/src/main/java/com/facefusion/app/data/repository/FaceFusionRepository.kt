package com.facefusion.app.data.repository

import com.facefusion.app.data.network.JobResponse
import com.facefusion.app.domain.result.AppResult
import kotlinx.coroutines.flow.Flow
import java.io.File

interface FaceFusionRepository {
    suspend fun health(baseUrl: String): AppResult<Boolean>
    suspend fun verify(baseUrl: String, token: String): AppResult<String>

    suspend fun createJob(baseUrl: String, token: String, mode: String): AppResult<JobResponse>
    suspend fun uploadSource(baseUrl: String, token: String, jobId: String, sourceFile: File, mime: String?): AppResult<JobResponse>
    suspend fun uploadTarget(baseUrl: String, token: String, jobId: String, targetFile: File, mime: String?): AppResult<JobResponse>
    suspend fun submit(baseUrl: String, token: String, jobId: String): AppResult<JobResponse>
    suspend fun getJob(baseUrl: String, token: String, jobId: String): AppResult<JobResponse>

    fun observeJobEvents(baseUrl: String, token: String, jobId: String): Flow<AppResult<JobResponse>>

    suspend fun downloadResult(baseUrl: String, token: String, jobId: String, targetKind: String): AppResult<File>
}
