package com.victor.traillink.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * First-run state (privacy accepted, tour "don't show again"). Kept under the old name/file so 2.2 installs keep it.
 * 2.3: the Places layers (Food, Travel, Schools, Military history, Community) moved to the Kodiak Maps app; their old
 * switches ("food", "travel", "mil", "schools", "community") are deleted from the prefs file on first load.
 */
object PoiPrefs {
    data class S(val privacyOk: Boolean = false, val tourNever: Boolean = false)
    private const val FILE = "poi_onboarding"
    val REMOVED_KEYS = listOf("food", "travel", "mil", "schools", "community")
    private val _s = MutableStateFlow(S())
    val state: StateFlow<S> get() = _s
    @Volatile private var loaded = false

    @Synchronized fun load(ctx: Context): S {
        if (loaded) return _s.value
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (REMOVED_KEYS.any { p.contains(it) }) p.edit().apply { REMOVED_KEYS.forEach { remove(it) } }.apply()
        _s.value = S(p.getBoolean("privacyOk", false), p.getBoolean("tourNever", false))
        loaded = true; return _s.value
    }

    @Synchronized fun update(ctx: Context, f: (S) -> S) {
        load(ctx); val n = f(_s.value); _s.value = n
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("privacyOk", n.privacyOk).putBoolean("tourNever", n.tourNever).apply()
    }
}
