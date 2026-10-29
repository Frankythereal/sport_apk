package de.endurofit

import android.app.Application
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.endurofit.data.Repo
import de.endurofit.health.FitImport
import de.endurofit.ui.AppRoot
import de.endurofit.ui.EnduroTheme

class App : Application() {
    private val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        // Widgets aktualisieren, sobald sich etwas ändert (Trinken, Essen, Schritte, Erholung …)
        appScope.launch {
            kotlinx.coroutines.flow.merge(
                Repo.water, Repo.food, Repo.steps, Repo.recovery, Repo.dayHr, Repo.goals, Repo.sessions, Repo.sick, de.endurofit.hr.Recorder.live,
            ).drop(1).debounce(1500).collect { de.endurofit.widget.Widgets.updateAll(this@App) }
        }
        // Kartendienst (OpenStreetMap) braucht eine Kennung und einen Zwischenspeicher
        org.osmdroid.config.Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = java.io.File(cacheDir, "osm")
            osmdroidTileCache = java.io.File(cacheDir, "osm/tiles")
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(this)
        takeShared(intent)
        setContent {
            EnduroTheme { AppRoot() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeShared(intent)
    }

    /** FIT-Dateien, die aus der Suunto-App geteilt wurden, zum Import vormerken. */
    private fun takeShared(intent: Intent?) {
        // Knopf in einem Widget gedrückt
        de.endurofit.widget.WidgetLinks.from(intent)?.let {
            de.endurofit.widget.WidgetLinks.pending.value = it
            return
        }
        val uris = FitImport.urisFrom(intent)
        if (uris.isNotEmpty()) {
            FitImport.pending.value = FitImport.pending.value + uris
            return
        }
        // Geteilter Text (z. B. Antwort aus ChatGPT) → Essen aus Text einfügen
        val text = if (intent?.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT) else null
        if (!text.isNullOrBlank()) {
            de.endurofit.ui.SharedFoodText.text.value = text
            de.endurofit.ui.Nav.openFood.value = true
        }
    }
}

/** Wird von Health Connect angezeigt, wenn du wissen willst, was die App mit den Daten macht. */
class PrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EnduroTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.systemBarsPadding().padding(24.dp)) {
                        Text("Datenschutz", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "EnduroFit liest aus Health Connect nur deine Trainings, Pulsdaten und " +
                                "Kalorien (z. B. von Polar Flow), um sie in der App auszuwerten. " +
                                "Alle Daten bleiben ausschließlich auf deinem Handy und werden " +
                                "nirgendwohin gesendet.",
                            Modifier.padding(top = 12.dp)
                        )
                        Button(onClick = { finish() }, modifier = Modifier.padding(top = 24.dp)) { Text("OK") }
                    }
                }
            }
        }
    }
}
