package de.endurofit.ui

import androidx.compose.ui.graphics.Color

import androidx.compose.ui.draw.clip

import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.foundation.layout.*

import androidx.compose.foundation.clickable

import androidx.compose.foundation.border

import androidx.compose.foundation.background

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
import androidx.compose.ui.unit.dp
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

    // Aufträge aus den Widgets
    val widgetLink by de.endurofit.widget.WidgetLinks.pending.collectAsState()
    LaunchedEffect(widgetLink) {
        val w = widgetLink ?: return@LaunchedEffect
        de.endurofit.widget.WidgetLinks.pending.value = null
        when {
            w == "today" -> tab = 0
            w == "food" || w == "supps" -> tab = 2
            w == "caffeine" -> { CaffeineNav.pick.value = true; tab = 2 }
            w == "supp_add" -> { Nav.suppAdd.value = true; tab = 2 }
            w == "exercise_next" -> ExerciseNav.open.value = de.endurofit.work.BreakMode.nextExercise(ctx).id
            w.startsWith("exercise_") -> ExerciseNav.open.value = w.removePrefix("exercise_")
            w.startsWith("food_") -> { Nav.foodAction.value = w.removePrefix("food_"); tab = 2 }
            w == "training" -> tab = 1
            w.startsWith("start_") -> {
                tab = 1
                startFromWidget(ctx, de.endurofit.data.SessionType.of(w.removePrefix("start_")))
            }
        }
    }

    val openFood by Nav.openFood.collectAsState()
    LaunchedEffect(openFood) {
        if (openFood) { tab = 2; Nav.openFood.value = false }
    }

    // Einrichtung beim ersten Start (oder aus dem Profil neu gestartet)
    val again by Nav.onboarding.collectAsState()
    var firstRun by remember { mutableStateOf(!Repo.onboarded) }
    if (firstRun || again) {
        Onboarding(onDone = { firstRun = false; Nav.onboarding.value = false; tab = 0 })
        return
    }

    val tabs = listOf("🏠" to "Heute", "❤️" to "Training", "🍽️" to "Essen", "📊" to "Analyse", "👤" to "Profil")

    Scaffold(
        containerColor = Ef.Ink,
        bottomBar = {
            Box(Modifier.fillMaxWidth().background(Ef.Ink).navigationBarsPadding().padding(bottom = 10.dp, top = 4.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Row(
                    Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF151B1A)).border(1.dp, Ef.Line, RoundedCornerShape(8.dp)).padding(5.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    tabs.forEachIndexed { i, (_, label) ->
                        val sel = tab == i
                        val bg by androidx.compose.animation.animateColorAsState(if (sel) Ef.PanelHi else Color.Transparent, label = "nav")
                        Column(
                            Modifier.clip(RoundedCornerShape(6.dp)).background(bg)
                                .clickable { if (i == 3 && tab == 3) openSession = null; tab = i }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                        ) {
                            NavIcon(i, if (sel) Accent else Ef.Muted)
                            androidx.compose.animation.AnimatedVisibility(sel) {
                                Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Accent, fontSize = 9.sp, modifier = Modifier.padding(top = 3.dp))
                            }
                        }
                    }
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

    ExerciseHost()

    fitResult?.let { r ->
        FitResultDialog(
            r,
            onOpen = { fitResult = null; openSession = it; tab = 3 },
            onDismiss = { fitResult = null },
        )
    }
}


/** Training aus dem Widget starten: Aufnahme beginnen und die zuletzt benutzte Verity Sense verbinden. */
private fun startFromWidget(ctx: android.content.Context, type: de.endurofit.data.SessionType) {
    if (de.endurofit.hr.Recorder.live.value == null) de.endurofit.hr.Recorder.start(type)
    val addr = Repo.lastDeviceAddress ?: return
    if (de.endurofit.hr.HrState.conn.value != de.endurofit.hr.HrState.Conn.IDLE) return
    val perms = if (android.os.Build.VERSION.SDK_INT >= 31)
        listOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
    else listOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
    val ok = perms.all { androidx.core.content.ContextCompat.checkSelfPermission(ctx, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
    if (ok) de.endurofit.hr.HrService.connect(ctx, addr)
}


/** Schlichte Linien-Symbole für die Navigation */
@Composable
private fun NavIcon(i: Int, c: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
        val w = size.width; val h = size.height
        val st = androidx.compose.ui.graphics.drawscope.Stroke(1.8.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        val p = androidx.compose.ui.graphics.Path()
        when (i) {
            0 -> { // Haus
                p.moveTo(w * 0.12f, h * 0.48f); p.lineTo(w * 0.5f, h * 0.14f); p.lineTo(w * 0.88f, h * 0.48f)
                p.moveTo(w * 0.22f, h * 0.4f); p.lineTo(w * 0.22f, h * 0.86f); p.lineTo(w * 0.78f, h * 0.86f); p.lineTo(w * 0.78f, h * 0.4f)
                p.moveTo(w * 0.42f, h * 0.86f); p.lineTo(w * 0.42f, h * 0.62f); p.lineTo(w * 0.58f, h * 0.62f); p.lineTo(w * 0.58f, h * 0.86f)
            }
            1 -> { // Puls
                p.moveTo(w * 0.04f, h * 0.55f); p.lineTo(w * 0.3f, h * 0.55f); p.lineTo(w * 0.4f, h * 0.25f)
                p.lineTo(w * 0.55f, h * 0.85f); p.lineTo(w * 0.66f, h * 0.45f); p.lineTo(w * 0.74f, h * 0.55f); p.lineTo(w * 0.96f, h * 0.55f)
            }
            2 -> { // Gabel & Messer
                p.moveTo(w * 0.3f, h * 0.1f); p.lineTo(w * 0.3f, h * 0.9f)
                p.moveTo(w * 0.18f, h * 0.1f); p.lineTo(w * 0.18f, h * 0.36f); p.quadraticTo(w * 0.3f, h * 0.48f, w * 0.42f, h * 0.36f); p.lineTo(w * 0.42f, h * 0.1f)
                p.moveTo(w * 0.72f, h * 0.9f); p.lineTo(w * 0.72f, h * 0.1f); p.quadraticTo(w * 0.86f, h * 0.3f, w * 0.84f, h * 0.55f); p.lineTo(w * 0.72f, h * 0.55f)
            }
            3 -> { // Balken
                p.moveTo(w * 0.2f, h * 0.88f); p.lineTo(w * 0.2f, h * 0.55f)
                p.moveTo(w * 0.5f, h * 0.88f); p.lineTo(w * 0.5f, h * 0.15f)
                p.moveTo(w * 0.8f, h * 0.88f); p.lineTo(w * 0.8f, h * 0.38f)
            }
            else -> { // Person
                drawCircle(c, w * 0.17f, androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.3f), style = st)
                p.moveTo(w * 0.16f, h * 0.9f); p.quadraticTo(w * 0.5f, h * 0.45f, w * 0.84f, h * 0.9f)
            }
        }
        drawPath(p, c, style = st)
    }
}
