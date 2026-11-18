package ai.antigem

import androidx.compose.runtime.Composable
import ai.antigem.ui.chat.ChatScreen
import ai.antigem.ui.chat.ChatViewModel

@Composable
fun MainNavigation(viewModel: ChatViewModel? = null) {
    if (viewModel != null) {
        ChatScreen(viewModel = viewModel)
    } else {
        ChatScreen()
    }
}
