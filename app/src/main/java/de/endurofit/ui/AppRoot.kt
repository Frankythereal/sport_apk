package de.endurofit.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.zIndex
import androidx.compose.animation.togetherWith
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import de.endurofit.ai.FoodVision
import de.endurofit.data.Repo
import de.endurofit.health.FitImport
import de.endurofit.health.SuuntoImport

@Composable
fun AppRoot() {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var openSession by rememberSaveable { mutableStateOf<String?>(null) }
    var fitResult by remember { mutableStateOf<FitImport.Result?>(null) }
    val pending by FitImport.pending.collectAsState()

    // Bei jedem Öffnen bzw. Zurückkehren in die App automatisch synchronisieren
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) de.endurofit.AutoSync.onAppVisible(ctx)
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val syncing by de.endurofit.AutoSync.running.collectAsState()

    // Geteilte Dateien: FIT-Dateien importieren, Fotos zur Essenserkennung schicken
    LaunchedEffect(pending) {
        if (pending.isEmpty()) return@LaunchedEffect
        val uris = pending
        FitImport.pending.value = emptyList()
        val (images, files) = uris.partition { ctx.contentResolver.getType(it)?.startsWith("image/") == true }
        images.firstOrNull()?.let {
            FoodVision.sharedImage.value = it
            tab = 2
        }
        if (files.isNotEmpty()) fitResult = FitImport.import(ctx, files)
    }

    val openFood by Nav.openFood.collectAsState()
    LaunchedEffect(openFood) {
        if (openFood) { tab = 2; Nav.openFood.value = false }
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
            if (syncing) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(androidx.compose.ui.Alignment.TopCenter).zIndex(1f))
            }
            androidx.compose.animation.AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) +
                        androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(260)) { w -> dir * w / 8 }) togetherWith
                        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(140))
                },
                label = "tabs",
            ) { t ->
            when (t) {
                0 -> TodayScreen(
                    onOpenTraining = { tab = 1 },
                    onOpenSession = { openSession = it; tab = 3 },
                    onOpenHeart = { openSession = null; Nav.analysisView.value = 2; tab = 3 },
                )
                1 -> TrainingScreen(onSaved = { openSession = it; tab = 3 })
                2 -> FoodScreen()
                3 -> AnalysisScreen(selected = openSession, onSelect = { openSession = it })
                else -> ProfileScreen(onOpenSession = { openSession = it; tab = 3 })
            }
            }
        }
    }

    fitResult?.let { r ->
        FitResultDialog(
            r,
            onOpen = { fitResult = null; openSession = it; tab = 3 },
            onDismiss = { fitResult = null },
        )
    }
}
