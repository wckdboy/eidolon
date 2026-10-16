package studio.eidolon.camera

import android.Manifest
import android.content.Context
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CameraInterfaceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun modeChangesKeepPreviewAttachedAndChoicesSurviveRelaunch() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val preferences = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
        val original = UiPreferencesStore(preferences).load()
        UiPreferencesStore(preferences).save(UiPreferences())
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitUntil(20_000) {
                    compose.onAllNodesWithContentDescription("Take photo").fetchSemanticsNodes().any {
                        !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
                    }
                }
                var preview: TextureView? = null
                scenario.onActivity { preview = texture(it.window.decorView) }
                assertNotNull(preview)
                compose.onNodeWithText("Pro").performClick().assertIsSelected()
                compose.onNodeWithText("Bloom").performClick().assertIsSelected()
                compose.onNodeWithContentDescription("Toggle composition grid").performClick().assertIsOn()
                compose.onNodeWithText("60%").performClick()
                compose.onNodeWithContentDescription("Bloom intensity").performSemanticsAction(
                    androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0.8f) }
                compose.onNodeWithText("80%").assertExists()
                scenario.onActivity { assertSame("Mode changes must not recreate the preview", preview, texture(it.window.decorView)) }
            }
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitUntil(10_000) { compose.onAllNodesWithText("Bloom").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Pro").assertIsSelected()
                compose.onNodeWithText("Bloom").assertIsSelected()
                compose.onNodeWithText("80%").assertExists()
                compose.onNodeWithContentDescription("Toggle composition grid").assertIsOn()
                compose.onNodeWithText("Fun").performClick().assertIsSelected()
                compose.onNodeWithText("SHUTTER").assertDoesNotExist()
            }
        } finally {
            UiPreferencesStore(preferences).save(original)
        }
    }

    private fun texture(view: View): TextureView? {
        if (view is TextureView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            texture(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
