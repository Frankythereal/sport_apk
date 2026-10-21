package de.endurofit.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import de.endurofit.data.Repo
import de.endurofit.health.SuuntoImport

@Composable
fun AppRoot() {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var openSession by rememberSaveable { mutableStateOf<String?>(null) }

    // Suunto-Daten beim Start still im Hintergrund nachladen (höchstens alle 15 Minuten)
    LaunchedEffect(Unit) {
        try {
            if (System.currentTimeMillis() - Repo.lastImport > 15 * 60_000L && SuuntoImport.hasPermissions(ctx)) {
                SuuntoImport.import(ctx, days = 7)
            }
        } catch (_: Exception) {
        }
    }

    val tabs = listOf("🏠" to "Heute", "❤️" to "Training", "🍽️" to "Essen", "📊" to "Analyse", "👤" to "Profil")

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, (emoji, label) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = {
                            if (i == 3 && tab == 3) openSession = null
                            tab = i
                        },
                        icon = { Text(emoji, fontSize = 20.sp) },
                        label = { Text(label, fontSize = 11.sp) },
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> TodayScreen(
                    onOpenTraining = { tab = 1 },
                    onOpenSession = { openSession = it; tab = 3 },
                )
                1 -> TrainingScreen(onSaved = { openSession = it; tab = 3 })
                2 -> FoodScreen()
                3 -> AnalysisScreen(selected = openSession, onSelect = { openSession = it })
                else -> ProfileScreen()
            }
        }
    }
}
