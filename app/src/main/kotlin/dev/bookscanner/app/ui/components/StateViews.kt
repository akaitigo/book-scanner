package dev.bookscanner.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

object StateViewTags {
    const val ERROR = "state-error"
    const val RETRY = "state-retry"
    const val LOADING = "state-loading"
    const val EMPTY = "state-empty"
}

/**
 * Loading indicator that says what is loading.
 *
 * A bare spinner tells the user only that something is happening somewhere,
 * which is not enough to judge whether to keep waiting.
 */
@Composable
fun LoadingState(
    message: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().testTag(StateViewTags.LOADING), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

/**
 * Error state with a way forward.
 *
 * Errors that only flash past in a snackbar leave the user on a screen that
 * looks empty, with nothing to press — indistinguishable from a broken app.
 * Every failure that leaves a screen without content gets a retry here.
 */
@Composable
fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    retryLabel: String = "Try again",
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Box(modifier.fillMaxSize().testTag(StateViewTags.ERROR), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onRetry, modifier = Modifier.testTag(StateViewTags.RETRY)) {
                Text(retryLabel)
            }
            if (secondaryLabel != null && onSecondary != null) {
                Button(onClick = onSecondary) { Text(secondaryLabel) }
            }
        }
    }
}

/** Empty state that explains the situation and offers the action that resolves it. */
@Composable
fun EmptyState(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().testTag(StateViewTags.EMPTY), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onAction, modifier = Modifier.padding(top = 8.dp)) { Text(actionLabel) }
        }
    }
}
