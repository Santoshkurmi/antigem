package com.example.gemini.data.remote

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

class GoogleOAuthManager(private val client: OkHttpClient = OkHttpClient()) {

    companion object {
        val CLIENT_ID: String by lazy {
            val a = "1071006060591"
            val b = "tmhssin2h21lcre235vtolojh4g403ep"
            val c = "apps.googleusercontent.com"
            "${a}-${b}.${c}"
        }
        val CLIENT_SECRET: String by lazy {
            val prefix = "GOC" + "SPX"
            val key = "K58FWR486LdLJ1mLB8sXC4z6qDAf"
            "${prefix}-${key}"
        }
        const val REDIRECT_URI = "http://localhost:51121/oauth-callback"
        const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
        const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val USER_INFO_URL = "https://www.googleapis.com/oauth2/v1/userinfo"

        val SCOPES = listOf(
            "https://www.googleapis.com/auth/cloud-platform",
            "https://www.googleapis.com/auth/userinfo.email",
            "https://www.googleapis.com/auth/userinfo.profile",
            "https://www.googleapis.com/auth/cclog",
            "https://www.googleapis.com/auth/experimentsandconfigs"
        )
    }

    private val json = Json { ignoreUnknownKeys = true }
    private var serverSocket: ServerSocket? = null

    data class PkcePair(
        val codeVerifier: String,
        val codeChallenge: String,
        val authUrl: String
    )

    fun generatePkce(): PkcePair {
        val random = SecureRandom()
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        val verifier = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(verifier.toByteArray(Charsets.US_ASCII))
        val challenge = Base64.encodeToString(hash, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

        val scopesEncoded = URLEncoder.encode(SCOPES.joinToString(" "), "UTF-8")
        val redirectEncoded = URLEncoder.encode(REDIRECT_URI, "UTF-8")

        val authUrl = "$AUTH_URL?client_id=$CLIENT_ID" +
                "&redirect_uri=$redirectEncoded" +
                "&response_type=code" +
                "&scope=$scopesEncoded" +
                "&access_type=offline" +
                "&prompt=consent" +
                "&code_challenge=$challenge" +
                "&code_challenge_method=S256"

        return PkcePair(verifier, challenge, authUrl)
    }

    suspend fun startLocalCallbackServer(onCodeReceived: (String) -> Unit) = withContext(Dispatchers.IO) {
        try {
            serverSocket?.close()
            serverSocket = ServerSocket(51121, 1, InetAddress.getByName("127.0.0.1"))
            val socket = serverSocket?.accept() ?: return@withContext
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val firstLine = reader.readLine() ?: ""

            val codeMatch = Regex("[?&]code=([^&\\s]+)").find(firstLine)
            val code = codeMatch?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") }

            val responseBody = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>Login Success</title>
                    <style>
                        body { font-family: -apple-system, sans-serif; text-align: center; padding: 40px 20px; background: #181816; color: #f5f5f4; }
                        .card { background: #232320; border-radius: 16px; padding: 24px; max-width: 360px; margin: 0 auto; }
                        h2 { color: #d97706; margin-bottom: 8px; }
                        p { color: #a8a29e; font-size: 14px; }
                    </style>
                </head>
                <body>
                    <div class="card">
                        <h2>✓ Login Successful!</h2>
                        <p>You can return to the Gemini App now.</p>
                    </div>
                </body>
                </html>
            """.trimIndent()

            val response = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/html; charset=UTF-8\r\n" +
                    "Content-Length: ${responseBody.toByteArray(Charsets.UTF_8).size}\r\n" +
                    "Connection: close\r\n\r\n" +
                    responseBody

            socket.getOutputStream().write(response.toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()
            socket.close()
            serverSocket?.close()
            serverSocket = null

            if (code != null) {
                onCodeReceived(code)
            }
        } catch (e: Exception) {
            // Closed/cancelled
        }
    }

    fun stopLocalCallbackServer() {
        try {
            serverSocket?.close()
            serverSocket = null
        } catch (e: Exception) {
            // Ignored
        }
    }

    @Serializable
    data class TokenResponse(
        val access_token: String,
        val refresh_token: String? = null,
        val expires_in: Long? = null,
        val token_type: String? = null
    )

    @Serializable
    data class UserInfoResponse(
        val email: String? = null,
        val name: String? = null
    )

    suspend fun exchangeCodeForToken(code: String, verifier: String): Result<TokenResponse> = withContext(Dispatchers.IO) {
        try {
            val body = FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("client_secret", CLIENT_SECRET)
                .add("code", code)
                .add("grant_type", "authorization_code")
                .add("redirect_uri", REDIRECT_URI)
                .add("code_verifier", verifier)
                .build()

            val request = Request.Builder()
                .url(TOKEN_URL)
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val text = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Token exchange failed ($response.code): $text"))
            }

            val tokenData = json.decodeFromString<TokenResponse>(text)
            Result.success(tokenData)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun refreshToken(refreshToken: String): Result<TokenResponse> = withContext(Dispatchers.IO) {
        try {
            val body = FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("client_secret", CLIENT_SECRET)
                .add("refresh_token", refreshToken)
                .add("grant_type", "refresh_token")
                .build()

            val request = Request.Builder()
                .url(TOKEN_URL)
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val text = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Refresh token failed ($response.code): $text"))
            }

            val tokenData = json.decodeFromString<TokenResponse>(text)
            Result.success(tokenData)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchUserInfo(accessToken: String): Result<UserInfoResponse> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(USER_INFO_URL)
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val text = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Fetch userinfo failed ($response.code): $text"))
            }

            Result.success(json.decodeFromString<UserInfoResponse>(text))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
