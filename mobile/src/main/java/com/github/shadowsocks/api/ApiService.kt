package com.github.shadowsocks.api

import retrofit2.Call
import retrofit2.http.GET

interface ApiService {
    @GET("getclientconfig")
    fun getClientConfig(): Call<ClientConfig>
}
