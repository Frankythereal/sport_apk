package de.endurofit.hr

import de.endurofit.data.Calc
import de.endurofit.data.GymSet
import de.endurofit.data.HrSample
import de.endurofit.data.Repo
import de.endurofit.data.SOURCE_SENSOR
import de.endurofit.data.Session
import de.endurofit.data.SessionType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Zustand der Sensorverbindung – wird vom Service gesetzt, von der Oberfläche gelesen. */
object HrState {
    enum class Conn { IDLE, CONNECTING, CONNECTED, RECONNECTING }

    val conn = MutableStateFlow(Conn.IDLE)
    val deviceName = MutableStateFlow<String?>(null)
    val bpm = MutableStateFlow(0)
    val lastBeatAt = MutableStateFlow(0L)
    val error = MutableStateFlow<String?>(null)
}

/** Nimmt eine laufende Trainingseinheit auf. */
object Recorder {
    data class Live(
        val id: String,
        val type: SessionType,
        val start: Long,
        val markers: List<Long> = emptyList(),
        val sets: List<GymSet> = emptyList(),
    )

    private val _live = MutableStateFlow<Live?>(null)
    val live: StateFlow<Live?> = _live

    private val samples = ArrayList<HrSample>()

    /** Zählt hoch, sobald ein neuer Pulswert dazukommt – damit die Anzeige neu zeichnet. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision

    private var lastCheckpoint = 0L

    @Synchronized
    fun start(type: SessionType) {
        if (_live.value != null) return
        samples.clear()
        val now = System.currentTimeMillis()
        _live.value = Live(id = "rec_$now", type = type, start = now)
        _revision.value++
        lastCheckpoint = now
    }

    @Synchronized
    fun addSample(bpm: Int) {
        if (_live.value == null || bpm <= 0) return
        val now = System.currentTimeMillis()
        samples.add(HrSample(now, bpm))
        _revision.value++
        // Alle 60 Sekunden zwischenspeichern, falls das Handy die App beendet
        if (now - lastCheckpoint > 60_000) {
            lastCheckpoint = now
            build()?.let { Repo.saveSession(it) }
        }
    }

    @Synchronized
    fun snapshot(): List<HrSample> = ArrayList(samples)

    @Synchronized
    fun addMarker() {
        val l = _live.value ?: return
        _live.value = l.copy(markers = l.markers + System.currentTimeMillis())
    }

    @Synchronized
    fun addSet(set: GymSet) {
        val l = _live.value ?: return
        _live.value = l.copy(sets = l.sets + set)
        build()?.let { Repo.saveSession(it) }
    }

    @Synchronized
    fun removeSet(index: Int) {
        val l = _live.value ?: return
        if (index !in l.sets.indices) return
        _live.value = l.copy(sets = l.sets.filterIndexed { i, _ -> i != index })
    }

    @Synchronized
    fun setType(type: SessionType) {
        val l = _live.value ?: return
        _live.value = l.copy(type = type)
    }

    @Synchronized
    private fun build(): Session? {
        val l = _live.value ?: return null
        val now = System.currentTimeMillis()
        val list = ArrayList(samples)
        return Session(
            id = l.id,
            type = l.type,
            title = l.type.label,
            start = l.start,
            end = now,
            source = SOURCE_SENSOR,
            samples = list,
            markers = l.markers,
            sets = l.sets,
            kcal = Calc.sessionKcal(list, Repo.profile.value),
        )
    }

    @Synchronized
    fun stop(): Session? {
        val s = build() ?: return null
        Repo.saveSession(s)
        _live.value = null
        samples.clear()
        _revision.value++
        return s
    }

    @Synchronized
    fun discard() {
        val l = _live.value ?: return
        Repo.deleteSession(l.id)
        _live.value = null
        samples.clear()
        _revision.value++
    }
}
