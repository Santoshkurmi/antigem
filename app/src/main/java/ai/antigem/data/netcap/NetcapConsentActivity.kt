package ai.antigem.data.netcap

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Shows Android's "Connection request" (VPN permission) dialog and reports the answer. */
class NetcapConsentActivity : ComponentActivity() {
    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        done(res.resultCode == RESULT_OK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = VpnService.prepare(this)
        if (intent == null) {
            done(true)
            return
        }
        if (savedInstanceState == null) runCatching { launcher.launch(intent) }.onFailure { done(false) }
    }

    private fun done(granted: Boolean) {
        pending?.complete(granted)
        pending = null
        finish()
    }

    companion object {
        @Volatile private var pending: CompletableDeferred<Boolean>? = null

        /** True once the user allowed the VPN (at once when it already was). */
        suspend fun ask(context: Context): Boolean {
            if (VpnService.prepare(context) == null) return true
            val d = CompletableDeferred<Boolean>()
            pending?.complete(false)
            pending = d
            val ok = runCatching {
                context.startActivity(Intent(context, NetcapConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (!ok) return false
            return withTimeoutOrNull(120_000) { d.await() } ?: false
        }
    }
}
