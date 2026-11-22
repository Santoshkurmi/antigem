package ai.antigem.ui.chat

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

object ChatViewModelHolder : ViewModelStoreOwner {
    private val globalStore = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = globalStore

    @Volatile
    private var instance: ChatViewModel? = null

    fun get(application: Application): ChatViewModel {
        return instance ?: synchronized(this) {
            instance ?: ViewModelProvider(
                this,
                ViewModelProvider.AndroidViewModelFactory.getInstance(application)
            )[ChatViewModel::class.java].also { instance = it }
        }
    }

    /** The chat view model if the app already made it in this process (null: the app's screen has not run yet). */
    fun peek(): ChatViewModel? = instance
}
