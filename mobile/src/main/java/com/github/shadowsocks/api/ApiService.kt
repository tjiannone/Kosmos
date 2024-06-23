package com.github.shadowsocks.api

import retrofit2.Call
import retrofit2.http.GET

interface ApiService {
    @GET("getclientconfig_auth")
    fun getClientConfig(): Call<ClientConfig>
}
