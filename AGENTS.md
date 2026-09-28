# AGY gRPC Service Guidelines

All communication with the Antigravity (AGY) daemon must use the type-safe Square Wire client **`AgyLanguageService`** (`com.example.gemini.data.remote.services.AgyLanguageService`).

Do not use manual JSON strings or raw dictionaries.

---

## 1. How to Call RPCs

### A. Unary Calls (Request-Response)
Use `.executeSafely(request)` to get a `Result<Response>`:

```kotlin
import com.example.gemini.data.remote.services.AgyLanguageService
import com.example.gemini.data.remote.services.executeSafely
import exa.language_server_pb.GetStatusRequest

suspend fun checkStatus() {
    AgyLanguageService.GetStatus()
        .executeSafely(GetStatusRequest())
        .onSuccess { response ->
            println("Status: ${response.status}")
        }
        .onFailure { error ->
            println("Error: ${error.message}")
        }
}
```

### B. Streaming Calls
Use `.asFlowSafely(request)` to get a coroutine `Flow<Response>`:

```kotlin
import com.example.gemini.data.remote.services.AgyLanguageService
import com.example.gemini.data.remote.services.asFlowSafely
import exa.language_server_pb.StreamAgentStateUpdatesRequest
import kotlinx.coroutines.flow.catch

suspend fun streamUpdates(cascadeId: String) {
    AgyLanguageService.StreamAgentStateUpdates()
        .asFlowSafely(StreamAgentStateUpdatesRequest(conversation_id = cascadeId))
        .catch { err -> println("Stream error: ${err.message}") }
        .collect { frame ->
            println("Update: ${frame.agent_state_update?.status}")
        }
}
```

---

## 2. Error Handling & CSRF Recovery

- **Automatic CSRF Token Recovery**: `AgyOkHttpClient` automatically intercepts HTTP `401`, `403`, or `grpc-status: 16`, refreshes the token via `AgyCsrfManager`, and retries the request seamlessly.
- **Typed Error Mapping**: `.executeSafely()` and `.asFlowSafely()` automatically map network and gRPC status errors into `AgyRpcError`:
  - `AgyRpcError.DaemonOffline` (connection refused / daemon stopped)
  - `AgyRpcError.Unauthenticated` (unauthorized / expired token)
  - `AgyRpcError.ServerError` (daemon internal error / non-zero gRPC status)
  - `AgyRpcError.Unknown`

---

## 3. Communication & User Interaction Rules

- **Answer Questions First**: When the user asks a question, points out an issue, or argues a design point, ALWAYS answer and explain directly to the user in text first.
- **Do Not Stealth-Edit Files**: Never start modifying code in silence instead of answering the user's question or addressing their direct feedback.
- **Format Parity**: Both JSON and binary gRPC return the exact same protobuf schema and fields from the daemon; never assume or claim they return different keys or formats.
- **Kotlin Type Inference**: Do not define unnecessary explicit types when Kotlin can infer types automatically. Rely on Kotlin's type inference and keep code concise unless explicit types are strictly required by the compiler.
- **Only do what user has told you to do**: Never touch a single line that user has not told to do, if user said to do Task X then do that nothing else not even add a signle extra space somewhere, if need such things to chagne mroe than user has asked for, alwasy explain user that you want to do this too.