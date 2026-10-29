package de.endurofit

import android.content.Context
import android.util.Log
import de.endurofit.data.Repo
import de.endurofit.health.SuuntoImport
import de.endurofit.polar.PolarApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Holt bei jedem Öffnen der App (auch beim Zurückkehren aus dem Hintergrund) die neuesten Daten:
 * erst Health Connect, dann Polar direkt. Höchstens einmal pro Minute.
 */
object AutoSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val running = MutableStateFlow(false)
    val lastRun = MutableStateFlow(0L)

    private const val MIN_GAP = 60_000L

    fun onAppVisible(ctx: Context, force: Boolean = false) {
        if (running.value) return
        if (!force && System.currentTimeMillis() - lastRun.value < MIN_GAP) return
        val appCtx = ctx.applicationContext
        running.value = true
        scope.launch {
            try {
                if (SuuntoImport.anyPermission(appCtx)) SuuntoImport.import(appCtx, days = 3)
            } catch (e: Exception) {
                Log.w("AutoSync", "Health Connect", e)
            }
            try {
                if (PolarApi.connected) {
                    PolarApi.sync(appCtx, days = 2)
                    PolarApi.refreshConnected()
                }
            } catch (e: Exception) {
                Log.w("AutoSync", "Polar", e)
            }
            lastRun.value = System.currentTimeMillis()
            running.value = false
        }
    }
}
