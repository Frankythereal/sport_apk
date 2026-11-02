package de.endurofit.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.cos
import kotlin.math.sin

// ---------- Strichmännchen, das die Übung vormacht ----------

/**
 * Körperhaltung. Winkel in Grad, absolut: 0 = nach unten, 90 = nach rechts, -90 = nach links, 180 = nach oben.
 * [t] neigt den Oberkörper (positiv = nach links), [head] neigt den Kopf zusätzlich,
 * [hx]/[hy] verschieben die Hüfte (Einheiten ≈ Rumpflänge; hy positiv = tiefer).
 * l… = hinterer/linker Arm bzw. Bein, r… = vorderer/rechter.
 */
data class Pose(
    val t: Float = 0f, val head: Float = 0f, val hx: Float = 0f, val hy: Float = 0f,
    val la: Float = -8f, val lf: Float = -4f, val ra: Float = 8f, val rf: Float = 4f,
    val lt: Float = -5f, val ls: Float = -5f, val rt: Float = 5f, val rs: Float = 5f,
) {
    fun lerp(o: Pose, f: Float): Pose {
        fun m(a: Float, b: Float) = a + (b - a) * f
        return Pose(m(t, o.t), m(head, o.head), m(hx, o.hx), m(hy, o.hy), m(la, o.la), m(lf, o.lf), m(ra, o.ra), m(rf, o.rf),
            m(lt, o.lt), m(ls, o.ls), m(rt, o.rt), m(rs, o.rs))
    }
}

enum class ExCat(val label: String, val emoji: String) {
    DEHNEN("Dehnen", "🧘"), MOBIL("Lockern", "🔄"), KRAFT("Kraft", "💪"), KREISLAUF("Kreislauf", "❤️"), RUHE("Entspannen", "😮‍💨")
}

data class Exercise(
    val id: String,
    val name: String,
    val cat: ExCat,
    /** Dauer in Sekunden (0 = nach Wiederholungen) */
    val seconds: Int,
    val reps: String,
    val steps: List<String>,
    val poses: List<Pose>,
    val periodMs: Int = 3000,
    val chair: Boolean = false,
    val breathe: Boolean = false,
    val search: String = "",
)

private val N = Pose()                                                           // vorne, stehend
private val S0 = Pose(la = 6f, lf = 6f, ra = 8f, rf = 8f, lt = 0f, ls = 0f, rt = 0f, rs = 0f)   // seitlich, stehend
private val SEAT = S0.copy(hx = -0.3f, hy = 0.6f, t = -8f, lt = 90f, ls = 0f, rt = 90f, rs = 0f, la = 50f, lf = 85f, ra = 55f, rf = 90f)

object ExerciseLib {
    val all = listOf(
        Exercise(
            "nacken", "Nacken dehnen", ExCat.DEHNEN, 40, "je Seite 20 Sek.",
            listOf("Aufrecht sitzen oder stehen, Schultern locker nach unten.", "Kopf langsam zur Seite neigen – Ohr Richtung Schulter.", "20 Sek. halten, ruhig atmen, dann Seite wechseln.", "Nicht ziehen oder wippen."),
            listOf(N.copy(head = 28f), N.copy(head = -28f)), periodMs = 7000, search = "Nacken dehnen Büro",
        ),
        Exercise(
            "armkreisen", "Armkreisen", ExCat.MOBIL, 30, "15× vorwärts, 15× rückwärts",
            listOf("Stehen, Füße hüftbreit.", "Arme seitlich in großen Kreisen bewegen.", "Nach der Hälfte die Richtung wechseln.", "Lockert Schultern nach langem Tippen."),
            listOf(N, N.copy(la = -90f, lf = -90f, ra = 90f, rf = 90f), N.copy(la = -172f, lf = -175f, ra = 172f, rf = 175f), N.copy(la = -90f, lf = -90f, ra = 90f, rf = 90f)),
            periodMs = 2600, search = "Armkreisen Übung",
        ),
        Exercise(
            "seitbeuge", "Seitbeuge", ExCat.DEHNEN, 40, "je Seite 3× 5 Sek.",
            listOf("Stehen, Füße hüftbreit.", "Einen Arm über den Kopf strecken.", "Oberkörper zur anderen Seite neigen, Hüfte bleibt gerade.", "Kurz halten, Seite wechseln."),
            listOf(N.copy(t = 18f, ra = 200f, rf = 215f, la = -14f, lf = -10f), N, N.copy(t = -18f, la = -200f, lf = -215f, ra = 14f, rf = 10f), N),
            periodMs = 8000, search = "Seitbeuge Dehnung stehend",
        ),
        Exercise(
            "kniebeuge", "Kniebeugen", ExCat.KRAFT, 0, "15 Wiederholungen",
            listOf("Füße schulterbreit, Zehen leicht nach außen.", "Po nach hinten unten schieben, als würdest du dich setzen.", "Knie bleiben über den Füßen, Rücken gerade.", "Kraftvoll zurück nach oben."),
            listOf(S0, S0.copy(hx = -0.25f, hy = 0.45f, t = -25f, lt = 70f, ls = -26f, rt = 70f, rs = -26f, la = 85f, lf = 85f, ra = 90f, rf = 90f)),
            periodMs = 3000, search = "Kniebeugen richtig",
        ),
        Exercise(
            "stuhl", "Aufstehen vom Stuhl", ExCat.KRAFT, 0, "10 Wiederholungen",
            listOf("Vorne auf die Stuhlkante setzen, Füße flach am Boden.", "Oberkörper leicht nach vorn, ohne Hände aufstehen.", "Langsam wieder hinsetzen – kurz antippen, sofort wieder hoch.", "Perfekt fürs Büro."),
            listOf(SEAT, S0.copy(la = 80f, lf = 85f, ra = 85f, rf = 90f)), periodMs = 3500, chair = true, search = "sit to stand Übung",
        ),
        Exercise(
            "beinheben", "Beinheben im Sitzen", ExCat.KRAFT, 0, "je Bein 10×",
            listOf("Aufrecht auf dem Stuhl sitzen.", "Ein Bein gerade nach vorn strecken, Fußspitze zu dir ziehen.", "2 Sek. halten, langsam absenken.", "Bein wechseln."),
            listOf(SEAT, SEAT.copy(rt = 88f, rs = 88f)), periodMs = 3000, chair = true, search = "Beinheben im Sitzen Büro",
        ),
        Exercise(
            "wade", "Wadenheben", ExCat.KRAFT, 0, "20 Wiederholungen",
            listOf("Aufrecht stehen, ggf. am Tisch festhalten.", "Auf die Zehenspitzen hochdrücken.", "Kurz oben halten, langsam absenken.", "Bringt den Kreislauf in den Beinen in Schwung."),
            listOf(S0, S0.copy(hy = -0.12f)), periodMs = 1800, search = "Wadenheben Übung",
        ),
        Exercise(
            "ausfall", "Ausfallschritt", ExCat.KRAFT, 0, "je Bein 8×",
            listOf("Aufrecht stehen.", "Großen Schritt nach vorn, hinteres Knie Richtung Boden.", "Vorderes Knie bleibt über dem Fuß.", "Zurückdrücken, Bein wechseln."),
            listOf(S0, S0.copy(hy = 0.35f, rt = 65f, rs = 0f, lt = -20f, ls = -61f, la = 5f, ra = 5f)), periodMs = 3200, search = "Ausfallschritt richtig",
        ),
        Exercise(
            "hampel", "Hampelmann", ExCat.KREISLAUF, 30, "30 Sekunden",
            listOf("Locker stehen.", "Springen und dabei Arme über den Kopf und Beine auseinander.", "Zurück in die Ausgangsposition.", "Ohne Springen: Beine abwechselnd seitlich tippen."),
            listOf(N, N.copy(la = -160f, lf = -165f, ra = 160f, rf = 165f, lt = -18f, ls = -18f, rt = 18f, rs = 18f, hy = -0.05f)),
            periodMs = 1100, search = "Hampelmann Übung",
        ),
        Exercise(
            "strecken", "Ganzkörper strecken", ExCat.MOBIL, 30, "5× langsam",
            listOf("Aufstehen.", "Arme weit nach oben, auf die Zehenspitzen.", "Ganz lang machen, tief einatmen.", "Ausatmen und locker lassen."),
            listOf(N, N.copy(la = -176f, lf = -178f, ra = 176f, rf = 178f, hy = -0.08f)), periodMs = 5000, search = "Ganzkörper strecken",
        ),
        Exercise(
            "quad", "Oberschenkel dehnen", ExCat.DEHNEN, 60, "je Seite 30 Sek.",
            listOf("Stehen, mit einer Hand am Tisch abstützen.", "Ferse zum Po ziehen, Fuß mit der Hand greifen.", "Knie zeigen nach unten, Hüfte leicht nach vorn.", "30 Sek. halten, Seite wechseln."),
            listOf(S0, S0.copy(lt = 5f, ls = 195f, la = -6f, lf = -6f, ra = 60f, rf = 70f)), periodMs = 6000, search = "Oberschenkel dehnen stehend",
        ),
        Exercise(
            "vorbeuge", "Vorbeuge", ExCat.DEHNEN, 30, "30 Sekunden",
            listOf("Füße hüftbreit, Knie leicht gebeugt.", "Oberkörper langsam nach vorn rollen.", "Arme hängen lassen, Kopf locker.", "Wirbel für Wirbel wieder aufrollen."),
            listOf(S0, S0.copy(t = -95f, hx = -0.15f, la = 5f, lf = 0f, ra = 8f, rf = 2f)), periodMs = 7000, search = "Vorbeuge Rücken dehnen",
        ),
        Exercise(
            "atmen", "Atempause", ExCat.RUHE, 60, "1 Minute",
            listOf("Bequem sitzen oder stehen, Augen schließen.", "4 Sekunden durch die Nase einatmen.", "6 Sekunden langsam ausatmen.", "Längeres Ausatmen beruhigt Puls und Kopf."),
            listOf(N, N.copy(la = -14f, ra = 14f, hy = -0.02f)), periodMs = 10000, breathe = true, search = "Atemübung 4 6",
        ),
        Exercise(
            "augen", "Augenpause 20-20-20", ExCat.RUHE, 20, "20 Sekunden",
            listOf("Vom Bildschirm wegschauen.", "20 Sekunden auf etwas in mindestens 6 m Entfernung schauen (Fenster!).", "Ein paar Mal bewusst blinzeln.", "Gut gegen müde, trockene Augen."),
            listOf(N.copy(head = 6f), N.copy(head = -6f)), periodMs = 6000, search = "20-20-20 Regel Augen",
        ),
    )

    fun byId(id: String?) = all.firstOrNull { it.id == id }

    /** Vorschlag für die nächste Pause – Abwechslung über die Kategorien. */
    fun suggestion(n: Int): Exercise {
        val order = listOf("nacken", "kniebeuge", "seitbeuge", "stuhl", "armkreisen", "wade", "augen", "quad", "hampel", "vorbeuge", "strecken", "beinheben", "atmen", "ausfall")
        return byId(order[Math.floorMod(n, order.size)]) ?: all.first()
    }
}

/** Animiertes Strichmännchen für eine Übung. */
@Composable
fun ExerciseFigure(ex: Exercise, modifier: Modifier, color: Color = Accent, running: Boolean = true) {
    val tr = rememberInfiniteTransition(label = "ex")
    val p by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(ex.periodMs, easing = LinearEasing), RepeatMode.Restart), label = "p")
    val prog = if (running) p else 0f
    val poses = ex.poses
    // Zwei Haltungen: hin und zurück, sonst reihum – jeweils mit kurzer Pause an jeder Haltung
    val seq = if (poses.size == 2) listOf(poses[0], poses[1]) else poses
    val n = seq.size
    val x = prog * n
    val i = x.toInt().coerceIn(0, n - 1)
    val local = x - i
    val eased = smooth(((local - 0.2f) / 0.6f).coerceIn(0f, 1f))
    val pose = seq[i].lerp(seq[(i + 1) % n], eased)

    Canvas(modifier) {
        val unit = size.height / 3.5f
        val origin = Offset(size.width / 2f, 2.15f * unit)
        drawFigure(pose, origin, unit, color, ex.chair)
        if (ex.breathe) {
            // Kreis wächst beim Einatmen (40 %) und schrumpft beim Ausatmen (60 %)
            val b = if (prog < 0.4f) prog / 0.4f else 1f - (prog - 0.4f) / 0.6f
            drawCircle(color.copy(alpha = 0.18f), unit * (0.35f + 0.35f * smooth(b)), origin + Offset(0f, -0.55f * unit))
        }
    }
}

private fun smooth(f: Float) = f * f * (3 - 2 * f)

private fun dir(a: Float): Offset {
    val r = Math.toRadians(a.toDouble())
    return Offset(sin(r).toFloat(), cos(r).toFloat())
}

private fun DrawScope.drawFigure(p: Pose, origin: Offset, u: Float, color: Color, chair: Boolean) {
    val floorY = origin.y + 1.2f * u
    // Boden
    drawLine(Color(0x33FFFFFF), Offset(size.width * 0.12f, floorY + 0.06f * u), Offset(size.width * 0.88f, floorY + 0.06f * u), strokeWidth = 0.04f * u)
    if (chair) {
        val seatY = origin.y + 0.66f * u
        val c = Color(0x66FFFFFF)
        drawRoundRect(c, Offset(origin.x - 0.62f * u, seatY), Size(0.62f * u, 0.08f * u), CornerRadius(4f))
        drawLine(c, Offset(origin.x - 0.58f * u, seatY), Offset(origin.x - 0.58f * u, floorY), strokeWidth = 0.05f * u)
        drawLine(c, Offset(origin.x - 0.06f * u, seatY), Offset(origin.x - 0.06f * u, floorY), strokeWidth = 0.05f * u)
        drawLine(c, Offset(origin.x - 0.62f * u, seatY), Offset(origin.x - 0.7f * u, seatY - 0.85f * u), strokeWidth = 0.06f * u)
    }
    val w = 0.13f * u
    val far = color.copy(alpha = 0.5f)
    val hip = origin + Offset(p.hx * u, p.hy * u)
    val shoulder = hip + dir(180f + p.t) * u
    fun limb(from: Offset, a1: Float, l1: Float, a2: Float, l2: Float, c: Color) {
        val j = from + dir(a1) * (l1 * u)
        val e = j + dir(a2) * (l2 * u)
        drawLine(c, from, j, strokeWidth = w, cap = StrokeCap.Round)
        drawLine(c, j, e, strokeWidth = w, cap = StrokeCap.Round)
    }
    // hinten: linker Arm und linkes Bein
    limb(hip, p.lt, 0.6f, p.ls, 0.6f, far)
    limb(shoulder, p.la, 0.52f, p.lf, 0.5f, far)
    // Rumpf und Kopf
    drawLine(color, hip, shoulder, strokeWidth = w * 1.25f, cap = StrokeCap.Round)
    val head = shoulder + dir(180f + p.t + p.head) * (0.34f * u)
    drawCircle(color, 0.19f * u, head)
    // vorne: rechtes Bein und rechter Arm
    limb(hip, p.rt, 0.6f, p.rs, 0.6f, color)
    limb(shoulder, p.ra, 0.52f, p.rf, 0.5f, color)
}
