package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.cinemaDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "honor600_pro_cam_prefs"
)

data class PersistedCinemaSettings(
    val favoriteLutIds: Set<String> = setOf("harcourt_classic_bw", "kodak_2383_print", "teal_orange"),
    val selectedLogProfileId: String = "rec709",
    val selectedLutId: String = "none",
    val lutIntensity: Float = 0.85f,
    val preferHevc: Boolean = true,
    val recordAudio: Boolean = true,
    val stabilizationEnabled: Boolean = true,
    val showRuleOfThirds: Boolean = true,
    val showSafeAreas: Boolean = false
)

class CinemaPreferencesRepository(private val context: Context) {

    private object Keys {
        val FAVORITE_LUTS = stringSetPreferencesKey("favorite_luts")
        val LOG_PROFILE = stringPreferencesKey("log_profile")
        val SELECTED_LUT = stringPreferencesKey("selected_lut")
        val LUT_INTENSITY = floatPreferencesKey("lut_intensity")
        val PREFER_HEVC = booleanPreferencesKey("prefer_hevc")
        val RECORD_AUDIO = booleanPreferencesKey("record_audio")
        val STABILIZATION = booleanPreferencesKey("stabilization")
        val RULE_OF_THIRDS = booleanPreferencesKey("rule_of_thirds")
        val SAFE_AREAS = booleanPreferencesKey("safe_areas")
    }

    val settingsFlow: Flow<PersistedCinemaSettings> = context.cinemaDataStore.data.map { prefs ->
        PersistedCinemaSettings(
            favoriteLutIds = prefs[Keys.FAVORITE_LUTS]
                ?: setOf("harcourt_classic_bw", "kodak_2383_print", "teal_orange"),
            selectedLogProfileId = prefs[Keys.LOG_PROFILE] ?: "rec709",
            selectedLutId = prefs[Keys.SELECTED_LUT] ?: "none",
            lutIntensity = prefs[Keys.LUT_INTENSITY] ?: 0.85f,
            preferHevc = prefs[Keys.PREFER_HEVC] ?: true,
            recordAudio = prefs[Keys.RECORD_AUDIO] ?: true,
            stabilizationEnabled = prefs[Keys.STABILIZATION] ?: true,
            showRuleOfThirds = prefs[Keys.RULE_OF_THIRDS] ?: true,
            showSafeAreas = prefs[Keys.SAFE_AREAS] ?: false
        )
    }

    suspend fun toggleFavoriteLut(lutId: String) {
        context.cinemaDataStore.edit { prefs ->
            val current = prefs[Keys.FAVORITE_LUTS]
                ?: setOf("harcourt_classic_bw", "kodak_2383_print", "teal_orange")
            prefs[Keys.FAVORITE_LUTS] = if (lutId in current) current - lutId else current + lutId
        }
    }

    suspend fun saveLogProfile(profileId: String) {
        context.cinemaDataStore.edit { it[Keys.LOG_PROFILE] = profileId }
    }

    suspend fun saveSelectedLut(lutId: String, intensity: Float) {
        context.cinemaDataStore.edit {
            it[Keys.SELECTED_LUT] = lutId
            it[Keys.LUT_INTENSITY] = intensity.coerceIn(0f, 1f)
        }
    }

    suspend fun saveRecordingPreferences(preferHevc: Boolean, recordAudio: Boolean, stabilization: Boolean) {
        context.cinemaDataStore.edit {
            it[Keys.PREFER_HEVC] = preferHevc
            it[Keys.RECORD_AUDIO] = recordAudio
            it[Keys.STABILIZATION] = stabilization
        }
    }

    suspend fun saveGuidePreferences(ruleOfThirds: Boolean, safeAreas: Boolean) {
        context.cinemaDataStore.edit {
            it[Keys.RULE_OF_THIRDS] = ruleOfThirds
            it[Keys.SAFE_AREAS] = safeAreas
        }
    }
}
