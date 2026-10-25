package com.aicode.feature.agreement.presentation

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.feature.agreement.data.AgreementRepository
import kotlinx.coroutines.launch

@Composable
fun AgreementGate(repository: AgreementRepository, content: @Composable (Boolean) -> Unit) {
    val accepted by repository.acceptedFlow.collectAsStateWithLifecycle(initialValue = null)
    content(accepted == true)
    if (accepted == false) {
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        AgreementDialog(
            onAccept = { scope.launch { repository.accept() } },
            onDecline = { (context as? Activity)?.finish() }
        )
    }
}
