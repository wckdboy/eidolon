package studio.eidolon.camera

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class UiPreferencesTest {
    @Test fun choicesSurviveReloadAndMalformedValuesFallBackSafely() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("ui-preferences-test", Context.MODE_PRIVATE)
        try {
            preferences.edit().clear().commit()
            val expected = UiPreferences(Look.BLOOM, 0.8f, Workspace.PRO, true, true)
            UiPreferencesStore(preferences).save(expected)
            assertEquals(expected, UiPreferencesStore(preferences).load())
            preferences.edit().putString("look", "REMOVED_MODE").putFloat("bloom", Float.NaN)
                .putInt("workspace", 7).putString("grid", "wrong type").commit()
            assertEquals(UiPreferences(permissionAsked = true), UiPreferencesStore(preferences).load())
            preferences.edit().putFloat("bloom", 5f).commit()
            assertEquals(1f, UiPreferencesStore(preferences).load().bloom)
            preferences.edit().putFloat("bloom", -1f).commit()
            assertEquals(0f, UiPreferencesStore(preferences).load().bloom)
        } finally {
            preferences.edit().clear().commit()
        }
    }
}
