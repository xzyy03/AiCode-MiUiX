package com.aicode.feature.agreement.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.agreementDataStore by preferencesDataStore(
    name = "agreement_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

@Singleton
class AgreementRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val ACCEPTED_KEY = booleanPreferencesKey("agreement_accepted")
    }

    val acceptedFlow: Flow<Boolean> =
        context.agreementDataStore.data.map { it[ACCEPTED_KEY] ?: false }

    suspend fun accept() {
        context.agreementDataStore.edit { it[ACCEPTED_KEY] = true }
    }
}
