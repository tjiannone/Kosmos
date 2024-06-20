package com.github.shadowsocks.api

import com.google.gson.annotations.SerializedName

data class ClientConfig(
    @SerializedName("Public IP") val publicIP: String,
    @SerializedName("Password") val password: String?,
    @SerializedName("Port") val port: String?,
    @SerializedName("Encryption") val encryption: String?,
    @SerializedName("Cloak Proxy Method") val cloakProxyMethod: String?,
    @SerializedName("ProxyMethod") val proxyMethod: String?,
    @SerializedName("EncryptionMethod") val encryptionMethod: String?,
    @SerializedName("UID") val uid: String?,
    @SerializedName("PublicKey") val publicKey: String?,
    @SerializedName("ServerName") val serverName: String?,
    @SerializedName("NumConn") val numConn: Int?,
    @SerializedName("BrowserSig") val browserSig: String?,
    @SerializedName("StreamTimeout") val streamTimeout: Int?
)

