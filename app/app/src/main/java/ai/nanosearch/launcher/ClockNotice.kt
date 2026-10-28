package ai.nanosearch.launcher

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/** Distinct events, including repeated equal text. An older timer cannot clear a newer result. */
internal class ClockNotices {
    var text by mutableStateOf<String?>(null); private set
    var revision by mutableLongStateOf(0L); private set
    fun show(value: String) { revision++; text = value }
    fun clear() { text = null }
    fun expire(expected: Long) { if (revision == expected) clear() }
}

/** Runs at screen scope so scrolling the card off-screen cannot restart its timeout. */
@Composable
internal fun ClockNoticeExpiry(text: String?, revision: Long, expire: (Long) -> Unit) {
    val accessibility = LocalAccessibilityManager.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val onExpire by rememberUpdatedState(expire)
    LaunchedEffect(text, revision, accessibility, lifecycle) {
        if (text == null) return@LaunchedEffect
        val timeout = accessibility?.calculateRecommendedTimeoutMillis(
            originalTimeoutMillis = 6_000L, containsIcons = false, containsText = true, containsControls = true,
        ) ?: 6_000L
        // Readable time while foregrounded; respect accessibility's extended/indefinite timeout.
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            delay(timeout)
            onExpire(revision)
        }
    }
}

@Composable
internal fun ClockNoticeCard(text: String, dismiss: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(text)
            TextButton(onClick = dismiss, modifier = Modifier.align(Alignment.Start).heightIn(min = 48.dp), contentPadding = PaddingValues(0.dp)) {
                // Keep the accessible minimum button size while its label shares the message's start.
                Text("Dismiss", Modifier.widthIn(min = ButtonDefaults.MinWidth), textAlign = TextAlign.Start)
            }
        }
    }
}
