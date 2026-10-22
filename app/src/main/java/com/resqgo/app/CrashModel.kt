package com.resqgo.app

import android.content.Context
import org.json.JSONObject
import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/** Order matters: it is the order of the weights in crash_model.json. */
val FEATURE_NAMES = listOf(
    "peak_g", "hit_excess", "above2g_frac", "pre_std", "post_std",
    "orient_deg", "speed_before", "speed_after", "has_speed",
)

private const val G = 9.80665

/**
 * The ML model's inputs, cut around the strongest hit in a clip. Same maths as features()
 * in ml/resqgo_ml.py; CrashModelTest checks the two agree. [kmh] is NaN where GPS had no speed.
 */
fun crashFeatures(t: DoubleArray, ax: DoubleArray, ay: DoubleArray, az: DoubleArray, kmh: DoubleArray): DoubleArray {
    val n = t.size
    val mag = DoubleArray(n) { sqrt(ax[it] * ax[it] + ay[it] * ay[it] + az[it] * az[it]) / G }
    var k = 0
    for (i in 1 until n) if (mag[i] > mag[k]) k = i
    val t0 = t[k]
    val pre = (0 until n).filter { t[it] >= t0 - 3000 && t[it] < t0 - 500 }
    val hit = (0 until n).filter { t[it] >= t0 - 500 && t[it] <= t0 + 500 }
    val post = (0 until n).filter { t[it] > t0 + 1500 && t[it] <= t0 + 3000 }
    val known = (pre + hit).filter { !kmh[it].isNaN() }
    val after = (0 until n).lastOrNull { t[it] <= t0 + 3000 && !kmh[it].isNaN() }
    return doubleArrayOf(
        mag[k],
        hit.sumOf { max(0.0, mag[it] - 1) } / hit.size,
        hit.count { mag[it] > 2 }.toDouble() / hit.size,
        std(pre.map { mag[it] }),
        std(post.map { mag[it] }),
        tilt(pre, post, ax, ay, az),
        known.maxOfOrNull { kmh[it] } ?: 0.0,
        after?.let { kmh[it] } ?: 0.0,
        if (known.isEmpty()) 0.0 else 1.0,
    )
}

private fun std(xs: List<Double>): Double {
    if (xs.size < 2) return 0.0
    val m = xs.sum() / xs.size
    return sqrt(xs.sumOf { (it - m) * (it - m) } / xs.size)
}

/** Degrees the phone tilted between before and after the hit. */
private fun tilt(pre: List<Int>, post: List<Int>, ax: DoubleArray, ay: DoubleArray, az: DoubleArray): Double {
    if (pre.isEmpty() || post.isEmpty()) return 0.0
    val a = listOf(ax, ay, az).map { c -> pre.sumOf { c[it] } / pre.size }
    val b = listOf(ax, ay, az).map { c -> post.sumOf { c[it] } / post.size }
    val na = sqrt(a.sumOf { it * it })
    val nb = sqrt(b.sumOf { it * it })
    if (na == 0.0 || nb == 0.0) return 0.0
    val c = (a.zip(b).sumOf { (x, y) -> x * y } / (na * nb)).coerceIn(-1.0, 1.0)
    return Math.toDegrees(acos(c))
}

/**
 * Logistic regression trained by ml/resqgo_ml.py. Shadow mode for now: the app records the score
 * with each clip and never lets it stop an alarm. A score under [threshold] means "looks like a false alarm".
 */
class CrashModel(
    val version: String,
    val demo: Boolean,
    private val mean: DoubleArray,
    private val std: DoubleArray,
    private val weights: DoubleArray,
    private val bias: Double,
    val threshold: Double,
) {
    fun score(f: DoubleArray): Double {
        var z = bias
        for (i in f.indices) z += weights[i] * ((f[i] - mean[i]) / std[i])
        return 1 / (1 + exp(-z))
    }

    companion object {
        fun parse(json: String): CrashModel {
            val o = JSONObject(json)
            val names = o.getJSONArray("features").let { a -> List(a.length()) { a.getString(it) } }
            require(names == FEATURE_NAMES) { "Model features $names don't match the app's $FEATURE_NAMES" }
            fun arr(key: String) = o.getJSONArray(key).let { a -> DoubleArray(a.length()) { a.getDouble(it) } }
            return CrashModel(
                o.getString("version"), o.optBoolean("demo"), arr("mean"), arr("std"), arr("weights"),
                o.getDouble("bias"), o.getDouble("threshold"),
            )
        }

        @Volatile private var loaded: Result<CrashModel>? = null

        /** assets/crash_model.json, or null when the build has none (or it doesn't match this app). */
        fun get(ctx: Context): CrashModel? =
            (loaded ?: runCatching { parse(ctx.assets.open("crash_model.json").bufferedReader().readText()) }
                .also { loaded = it }).getOrNull()
    }
}
