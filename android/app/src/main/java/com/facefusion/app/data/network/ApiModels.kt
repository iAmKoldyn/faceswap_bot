package com.facefusion.app.data.network

import com.google.gson.annotations.SerializedName

data class JobCreateRequest(
    @SerializedName("mode") val mode: String,
)

data class HealthResponse(
    @SerializedName("status") val status: String,
)

data class AuthVerifyResponse(
    @SerializedName("user_id") val userId: String,
)

data class JobResponse(
    @SerializedName("job_id") val jobId: String,
    @SerializedName("status") val status: String,
    @SerializedName("mode") val mode: String,
    @SerializedName("target_kind") val targetKind: String,
    @SerializedName("owner_id") val ownerId: String,
    @SerializedName("progress") val progress: Int,
    @SerializedName("stage") val stage: String?,
)
