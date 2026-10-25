package com.aicode.feature.agreement.presentation

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import com.aicode.R
import com.aicode.core.theme.semanticColors

@Composable
fun AgreementDialog(onAccept: () -> Unit, onDecline: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val termsUrl = stringResource(R.string.agreement_terms_url)
    val privacyUrl = stringResource(R.string.agreement_privacy_url)
    val linkStyles = TextLinkStyles(
        style = SpanStyle(
            color = MaterialTheme.semanticColors.info,
            textDecoration = TextDecoration.Underline
        )
    )
    val notice = buildAnnotatedString {
        append(stringResource(R.string.agreement_notice_prefix))
        withLink(
            LinkAnnotation.Clickable("terms", linkStyles) {
                runCatching { uriHandler.openUri(termsUrl) }
            }
        ) {
            append(stringResource(R.string.agreement_terms_link))
        }
        append(stringResource(R.string.agreement_notice_conjunction))
        withLink(
            LinkAnnotation.Clickable("privacy", linkStyles) {
                runCatching { uriHandler.openUri(privacyUrl) }
            }
        ) {
            append(stringResource(R.string.agreement_privacy_link))
        }
        append(stringResource(R.string.agreement_notice_suffix))
    }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.agreement_title)) },
        text = {
            Text(
                text = notice,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text(stringResource(R.string.agreement_accept))
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline) {
                Text(stringResource(R.string.agreement_decline))
            }
        }
    )
}
