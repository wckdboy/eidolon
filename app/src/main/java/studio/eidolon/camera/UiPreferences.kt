package studio.eidolon.camera

import android.content.SharedPreferences

/** Creative choices persist; device-specific exposure and focus always start in Auto. */
internal data class UiPreferences(
    val look: Look = Look.PURE,
    val bloom: Float = 0.6f,
    val workspace: Workspace = Workspace.FUN,
    val grid: Boolean = false,
    val permissionAsked: Boolean = false,
)

internal class UiPreferencesStore(private val preferences: SharedPreferences) {
    private var lastSaved: UiPreferences? = null
    // Read on IO once. A malformed or older preference must never prevent camera startup.
    fun load(): UiPreferences {
        val values = preferences.all
        return UiPreferences(
            look = Look.entries.firstOrNull { it.name == values["look"] } ?: Look.PURE,
            bloom = (values["bloom"] as? Float)?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.6f,
            workspace = Workspace.entries.firstOrNull { it.name == values["workspace"] } ?: Workspace.FUN,
            grid = values["grid"] as? Boolean ?: false,
            permissionAsked = values["permissionAsked"] as? Boolean ?: false,
        ).also { lastSaved = it }
    }

    fun save(value: UiPreferences) {
        if (value == lastSaved) return
        lastSaved = value
        preferences.edit().putString("look", value.look.name).putFloat("bloom", value.bloom)
            .putString("workspace", value.workspace.name).putBoolean("grid", value.grid)
            .putBoolean("permissionAsked", value.permissionAsked).apply()
    }
}
