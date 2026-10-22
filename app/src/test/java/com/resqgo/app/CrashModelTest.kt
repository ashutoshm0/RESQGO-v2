package com.resqgo.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app must compute exactly what ml/resqgo_ml.py trained on. Fixtures in src/test/resources/parity
 * come from the Python side; to refresh them after changing features():
 *   python ml/resqgo_ml.py score <clip.csv> <model.json> > <clip>.expected.json
 */
class CrashModelTest {
    private fun res(name: String) = javaClass.getResource("/parity/$name")!!.readText()

    private fun featuresOf(csv: String): DoubleArray {
        val lines = csv.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        val head = lines.first().split(',')
        val rows = lines.drop(1).map { it.split(',') }
        fun col(name: String) = head.indexOf(name).let { c -> DoubleArray(rows.size) { rows[it][c].toDoubleOrNull() ?: Double.NaN } }
        return crashFeatures(col("t_ms"), col("ax"), col("ay"), col("az"), col("speed_kmh"))
    }

    private fun check(clip: String) {
        val model = CrashModel.parse(res("model.json"))
        val expected = JSONObject(res("$clip.expected.json"))
        val want = expected.getJSONArray("features")
        val got = featuresOf(res("$clip.csv"))
        for (i in FEATURE_NAMES.indices) {
            assertEquals("${FEATURE_NAMES[i]} in $clip", want.getDouble(i), got[i], 1e-9 * maxOf(1.0, Math.abs(got[i])))
        }
        assertEquals("score of $clip", expected.getDouble("score"), model.score(got), 1e-9)
    }

    @Test fun crashMatchesPython() = check("crash")

    @Test fun bumpWithoutGpsMatchesPython() = check("bump_no_gps")

    @Test fun crashScoresAboveThresholdAndBumpBelow() {
        val model = CrashModel.parse(res("model.json"))
        assertTrue(model.score(featuresOf(res("crash.csv"))) > model.threshold)
        assertTrue(model.score(featuresOf(res("bump_no_gps.csv"))) < model.threshold)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aModelWithDifferentFeaturesIsRejected() {
        CrashModel.parse(res("model.json").replace("\"peak_g\"", "\"peak\""))
    }
}
