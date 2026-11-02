package de.endurofit.data

/**
 * Uhren-Hersteller und wie ihre Daten in die App kommen. Fast alle schreiben über ihre
 * eigene App in Health Connect – EnduroFit liest von dort. Polar geht zusätzlich direkt.
 */
data class WatchBrand(
    val id: String,
    val name: String,
    val app: String,
    val emoji: String,
    /** Paketnamen der Hersteller-App(s) – daran erkennt EnduroFit die Daten in Health Connect */
    val packages: List<String>,
    /** So schaltest du die Weitergabe an Health Connect ein */
    val steps: List<String>,
    /** Was erfahrungsgemäß ankommt */
    val gets: String,
    val healthConnect: Boolean = true,
    val note: String = "",
    val polarDirect: Boolean = false,
    val fitImport: Boolean = false,
)

object Watches {
    val all = listOf(
        WatchBrand(
            "polar", "Polar", "Polar Flow", "🔴", listOf("fi.polar"),
            listOf("Polar Flow öffnen und die Uhr synchronisieren.", "Menü (☰) → Allgemeine Einstellungen → Health Connect → einschalten.", "Alle Datenarten erlauben."),
            "Trainings, Puls, Schritte, Schlaf, Kalorien – direkt über Polar zusätzlich Erholung, HRV, Hauttemperatur, EKG",
            polarDirect = true, fitImport = true,
        ),
        WatchBrand(
            "xiaomi", "Xiaomi", "Mi Fitness", "🟠", listOf("com.mi.health", "com.xiaomi.wearable", "com.xiaomi.hm.health"),
            listOf("Mi Fitness auf die neueste Version bringen.", "Profil (unten rechts) → Einstellungen bzw. „Drittanbieter-Dienste“ → Health Connect → einschalten.", "In Health Connect alle Datenarten erlauben – auch „Schlaf“.", "Uhr in Mi Fitness einmal synchronisieren."),
            "Schritte, Puls, Schlaf mit Phasen, Kalorien, Trainings, Blutsauerstoff (je nach Modell)",
            note = "Schlaf kommt oft erst, nachdem du morgens Mi Fitness geöffnet hast.",
        ),
        WatchBrand(
            "garmin", "Garmin", "Garmin Connect", "🔵", listOf("com.garmin"),
            listOf("Garmin Connect öffnen → Mehr → Einstellungen → Verbundene Apps → Health Connect.", "Weitergabe einschalten und alle Datenarten erlauben."),
            "Trainings, Schritte, Puls, Schlaf, Kalorien", fitImport = true,
        ),
        WatchBrand(
            "samsung", "Samsung Galaxy Watch", "Samsung Health", "⚪", listOf("com.sec.android.app.shealth"),
            listOf("Samsung Health öffnen → ⋮ → Einstellungen → Health Connect.", "Weitergabe einschalten und alle Datenarten erlauben."),
            "Schritte, Puls, Schlaf, Trainings, Blutsauerstoff, Gewicht",
        ),
        WatchBrand(
            "suunto", "Suunto", "Suunto App", "⚫", listOf("com.stt.android", "com.suunto"),
            listOf("Suunto-App → Profil → Einstellungen → Health Connect → einschalten.", "Klappt das nicht: Training in der Suunto-App teilen → EnduroFit (FIT-Datei)."),
            "Trainings, Schritte, teils Puls und Schlaf", fitImport = true,
            note = "Die Suunto-App gibt nicht alles an Health Connect weiter – Trainings gehen sicher per FIT-Export.",
        ),
        WatchBrand(
            "amazfit", "Amazfit", "Zepp", "🟢", listOf("com.huami.watch.hmwatchmanager"),
            listOf("Zepp öffnen → Profil → Verbundene Konten bzw. Health Connect.", "Einschalten und alle Datenarten erlauben."),
            "Schritte, Puls, Schlaf, Trainings, Blutsauerstoff",
        ),
        WatchBrand(
            "google", "Pixel Watch / Fitbit", "Fitbit", "🔷", listOf("com.fitbit.FitbitMobile", "com.google.android.apps.fitness"),
            listOf("Fitbit-App → Profil → Einstellungen → Health Connect.", "Weitergabe einschalten und alle Datenarten erlauben."),
            "Schritte, Puls, Schlaf, Trainings, Blutsauerstoff",
        ),
        WatchBrand(
            "coros", "Coros", "Coros", "🟤", listOf("coros"),
            listOf("Coros-App → Profil → Einstellungen → Drittanbieter → Health Connect (falls angeboten).", "Sonst: Training exportieren → FIT-Datei mit EnduroFit teilen."),
            "Trainings, teils Schritte und Puls", fitImport = true,
        ),
        WatchBrand(
            "huawei", "Huawei", "Huawei Health", "🟥", listOf("com.huawei.health", "nl.appyhapps.healthsync"),
            listOf("Huawei Health gibt nichts an Health Connect weiter.", "Umweg: die App „Health Sync“ (kostenpflichtig) überträgt Huawei-Daten nach Health Connect.", "In Health Sync als Ziel „Health Connect“ wählen."),
            "über Health Sync: Schritte, Puls, Schlaf, Trainings", healthConnect = false,
        ),
        WatchBrand(
            "none", "Keine Uhr / nur Handy", "Handy", "📱", emptyList(),
            listOf("Schritte zählt dein Handy (Samsung Health / Google) – einfach Health Connect erlauben.", "Puls beim Training bekommst du mit einem Bluetooth-Pulsgurt oder Armband (z. B. Polar Verity Sense)."),
            "Schritte vom Handy, Puls über einen Bluetooth-Sensor beim Training",
        ),
    )

    fun byId(id: String?) = all.firstOrNull { it.id == id }

    fun selected(): WatchBrand? = byId(Repo.watchBrand)

    /** Stammen die Daten von einer Uhr (nicht vom Handy)? */
    fun isWatchPackage(pkg: String): Boolean =
        all.any { b -> b.id != "none" && b.id != "samsung" && b.packages.any { pkg.startsWith(it) || pkg.contains(it, true) } } ||
            (Repo.watchBrand == "samsung" && pkg == "com.sec.android.app.shealth")

    /** Gehört das Paket zur ausgewählten Uhr? Solche Daten werden bevorzugt. */
    fun isSelected(pkg: String): Boolean {
        val b = selected() ?: return false
        return b.packages.any { pkg.startsWith(it) || pkg.contains(it, true) }
    }

    fun labelFor(pkg: String): String? =
        all.firstOrNull { b -> b.packages.any { pkg.startsWith(it) || pkg.contains(it, true) } }?.let { it.app }
}
