package com.github.shadowsocks

import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.Response

class BasicAuthInterceptor(username: String, password: String) : Interceptor {

    private val credentials: String = Credentials.basic(username, password)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val authenticatedRequest = request.newBuilder()
            .header("Authorization", credentials)
            .build()
        return chain.proceed(authenticatedRequest)
    }
}
