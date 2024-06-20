package com.github.shadowsocks.api

import com.google.gson.annotations.SerializedName

data class InstanceResponse(
    @SerializedName("publicIP") val publicIP: String
)
