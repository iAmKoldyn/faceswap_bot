package com.facefusion.app.domain.result

sealed interface AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>
    data class Error(
        val type: ErrorType,
        val message: String? = null,
        val cause: Throwable? = null,
    ) : AppResult<Nothing>
}

enum class ErrorType {
    AUTH,
    NETWORK,
    TIMEOUT,
    VALIDATION,
    SERVER,
    UNKNOWN,
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> {
    return when (this) {
        is AppResult.Success -> AppResult.Success(transform(data))
        is AppResult.Error -> this
    }
}

fun <T> AppResult<T>.getOrNull(): T? = (this as? AppResult.Success)?.data
