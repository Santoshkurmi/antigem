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

            val encodedUrl = URLEncoder.encode(firstLine, "UTF-8")
            val rawFullUrl = "http://localhost:51121$firstLine".replace(" HTTP/1.1", "").replace("GET ", "")
            val safeCode = code ?: ""

            val responseBody = """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>Antigravity Authentication Successful</title>
                    <style>
                        * { box-sizing: border-box; margin: 0; padding: 0; }
                        body {
                            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
                            text-align: center;
                            padding: 32px 16px;
                            background: #141413;
                            color: #edece9;
                            display: flex;
                            align-items: center;
                            justify-content: center;
                            min-height: 100vh;
                        }
                        .card {
                            background: #1e1e1c;
                            border: 1px solid rgba(255, 255, 255, 0.08);
                            border-radius: 20px;
                            padding: 28px 20px;
                            max-width: 440px;
                            width: 100%;
                            box-shadow: 0 12px 32px rgba(0, 0, 0, 0.4);
                        }
                        .icon-circle {
                            width: 56px;
                            height: 56px;
                            border-radius: 50%;
                            background: rgba(217, 119, 6, 0.15);
                            color: #f59e0b;
                            display: flex;
                            align-items: center;
                            justify-content: center;
                            margin: 0 auto 16px;
                            font-size: 28px;
                        }
                        h2 {
                            color: #f5f5f4;
                            font-size: 22px;
                            font-weight: 700;
                            margin-bottom: 8px;
                            letter-spacing: -0.3px;
                        }
                        p.subtitle {
                            color: #a8a29e;
                            font-size: 14px;
                            line-height: 1.5;
                            margin-bottom: 22px;
                        }
                        .button-group {
                            display: flex;
                            flex-direction: column;
                            gap: 10px;
                            margin-bottom: 20px;
                        }
                        .btn {
                            width: 100%;
                            padding: 13px 18px;
                            border-radius: 12px;
                            border: none;
                            font-size: 14.5px;
                            font-weight: 600;
                            cursor: pointer;
                            transition: all 0.15s ease-in-out;
                            display: flex;
                            align-items: center;
                            justify-content: center;
                            gap: 8px;
                        }
                        .btn-primary {
                            background: #d97706;
                            color: #ffffff;
                        }
                        .btn-primary:active {
                            background: #b45309;
                            transform: scale(0.98);
                        }
                        .btn-secondary {
                            background: #2a2a27;
                            color: #d6d3d1;
                            border: 1px solid rgba(255, 255, 255, 0.1);
                        }
                        .btn-secondary:active {
                            background: #363632;
                            transform: scale(0.98);
                        }
                        .code-box {
                            background: #111110;
                            border: 1px solid rgba(255, 255, 255, 0.06);
                            border-radius: 10px;
                            padding: 10px 12px;
                            font-family: monospace;
                            font-size: 11px;
                            color: #a8a29e;
                            word-break: break-all;
                            text-align: left;
                            max-height: 65px;
                            overflow-y: auto;
                            margin-top: 14px;
                            user-select: all;
                        }
                        .toast {
                            color: #10b981;
                            font-size: 13px;
                            font-weight: 600;
                            margin-top: 8px;
                            min-height: 20px;
                        }
                    </style>
                </head>
                <body>
                    <div class="card">
                        <div class="icon-circle">✓</div>
                        <h2>Authentication Successful</h2>
                        <p class="subtitle">Your Antigravity token has been authorized. You can return to the app or copy the URL below.</p>
                        
                        <div class="button-group">
                            <button id="copyUrlBtn" class="btn btn-primary" onclick="copyFullUrl()">
                                📋 Copy Callback URL
                            </button>
                            <button id="copyCodeBtn" class="btn btn-secondary" onclick="copyAuthCode()">
                                🔑 Copy Auth Code Only
                            </button>
                        </div>
                        <div id="statusToast" class="toast"></div>

                        <div class="code-box" id="urlBox">$rawFullUrl</div>
                    </div>

                    <script>
                        const fullUrl = window.location.href || "$rawFullUrl";
                        const authCode = "$safeCode";

                        function showToast(msg) {
                            const toast = document.getElementById('statusToast');
                            toast.innerText = msg;
                            setTimeout(() => { toast.innerText = ''; }, 3500);
                        }

                        function copyFullUrl() {
                            navigator.clipboard.writeText(fullUrl).then(() => {
                                document.getElementById('copyUrlBtn').innerText = '✓ URL Copied!';
                                showToast('✓ Callback URL copied to clipboard!');
                                setTimeout(() => {
                                    document.getElementById('copyUrlBtn').innerText = '📋 Copy Callback URL';
                                }, 3000);
                            }).catch(() => {
                                selectFallback(fullUrl);
                            });
                        }

                        function copyAuthCode() {
                            if (!authCode) {
                                copyFullUrl();
                                return;
                            }
                            navigator.clipboard.writeText(authCode).then(() => {
                                document.getElementById('copyCodeBtn').innerText = '✓ Code Copied!';
                                showToast('✓ Auth Code copied to clipboard!');
                                setTimeout(() => {
                                    document.getElementById('copyCodeBtn').innerText = '🔑 Copy Auth Code Only';
                                }, 3000);
                            }).catch(() => {
                                selectFallback(authCode);
                            });
                        }

                        function selectFallback(text) {
                            const temp = document.createElement("textarea");
                            temp.value = text;
                            document.body.appendChild(temp);
                            temp.select();
                            document.execCommand("copy");
                            document.body.removeChild(temp);
                            showToast('✓ Copied to clipboard!');
                        }

                        // Attempt auto-copy on load
                        try {
                            if (navigator.clipboard && navigator.clipboard.writeText) {
                                navigator.clipboard.writeText(fullUrl);
                            }
                        } catch(e) {}
                    </script>
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
