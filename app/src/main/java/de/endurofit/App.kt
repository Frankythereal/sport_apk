package de.endurofit

import android.app.Application
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
import de.endurofit.ui.AppRoot
import de.endurofit.ui.EnduroTheme

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(this)
        setContent {
            EnduroTheme { AppRoot() }
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
                                "Kalorien (z. B. von der Suunto-App), um sie in der App auszuwerten. " +
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
