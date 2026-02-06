package com.facefusion.app.data.network

import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.http.FieldMap
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path

interface FaceFusionApi {
    @GET("health")
    suspend fun health(): HealthResponse

    @GET("auth/verify")
    suspend fun verify(
        @Header("Authorization") auth: String,
    ): AuthVerifyResponse

    @POST("jobs")
    suspend fun createJob(
        @retrofit2.http.Body body: JobCreateRequest,
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
