package com.seenot.app.ai

sealed class AiRequestFailure(message: String, cause: Throwable? = null) : IllegalStateException(message, cause) {
    class Offline(cause: Throwable? = null) : AiRequestFailure("DEVICE_OFFLINE", cause)

    class Reachability(cause: Throwable? = null) : AiRequestFailure("NETWORK_UNREACHABLE", cause)

    class ServiceUnavailable(val statusCode: Int) : AiRequestFailure("SERVICE_UNAVAILABLE:$statusCode")

    class Auth(val statusCode: Int) : AiRequestFailure("AUTH_FAILED:$statusCode")

    class RequestFailed(val statusCode: Int) : AiRequestFailure("MODEL_REQUEST_FAILED:$statusCode")
}
