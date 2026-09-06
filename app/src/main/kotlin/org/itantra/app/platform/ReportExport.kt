package org.itantra.app.platform

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.itantra.bench.ReportBundle
import org.itantra.bench.ResourceSample
import org.itantra.bench.RunConditions
import org.itantra.bench.ScorecardRow
import org.itantra.bench.UtteranceTrace
import java.io.File
import java.time.LocalDate

/**
 * Writes the three result files, or says exactly why it will not.
 *
 * ## Why this exists
 *
 * `MetricsScreen` rendered an EXPORT CSV row naming all three files, and `AppNavigation`
 * passed `onExportCsv = { }` behind it. Pressing it did nothing, which is the same defect
 * as the transport chooser that offered one option and the storage screen that offered a
 * delete: a control that looks like it acts and does not.
 *
 * ## Why a refusal is the useful outcome, not the failure
 *
 * [ReportBundle] will not write files for a run that `docs/EVALUATION.md` section 1 says
 * is not reportable — a debug build, an emulator, a short soak, a handset on charge, fewer
 * than a hundred utterances. That is the point of it: figures produced outside those
 * conditions end up on a slide with nothing to say where they came from.
 *
 * So most presses of this control will *not* produce files, and the valuable thing is
 * that it now says which condition failed and by how much — "soaked 3 minutes, and the
 * requirement is 30" — instead of appearing to work. Every unmet condition is listed at
 * once, because being told about the build and then about the battery is two wasted runs.
 *
 * ## What is not here yet
 *
 * Nothing samples CPU, memory or thermal state on the live path, so `samples` is empty and
 * `resource.csv` is written with its header and no rows. `scorecard.csv` is the same. That
 * is stated rather than hidden: the file is honest about being empty, and task W8.4's idle
 * CPU trace is what fills it. `latency.csv` is the one with real rows in it, because
 * `UtteranceClock` is on the live path already.
 */
class ReportExport(private val context: Context) {
    /**
     * @param traces the utterances timed so far, from the engine.
     * @param soakMinutes how long the engine has been running.
     * @return a sentence for the status line. Never throws: this is behind a button.
     */
    fun export(
        traces: List<UtteranceTrace>,
        soakMinutes: Int,
        samples: List<ResourceSample> = emptyList(),
        scorecard: List<ScorecardRow> = emptyList(),
    ): String {
        val conditions = conditions(soakMinutes)
        val unmet = conditions.unmetRequirements(traces)
        if (unmet.isNotEmpty()) {
            return "Not reportable yet — " + unmet.joinToString("; ")
        }

        val files =
            runCatching { ReportBundle(conditions).write(traces, samples, scorecard) }
                .getOrElse { return "Could not build the report: ${it.message}" }

        val written = ArrayList<String>()
        var where: String? = null
        for ((name, body) in files) {
            val at = write(name, body) ?: continue
            written += name
            where = at
        }
        return when {
            written.isEmpty() -> "Could not write the files. Storage may be full."
            else -> "Wrote ${written.size} files to $where"
        }
    }

    /**
     * The conditions this run actually meets, read from the device rather than declared.
     *
     * `isReleaseBuild` comes from the debuggable flag rather than `BuildConfig`, because
     * `buildFeatures { buildConfig }` is off and turning it on to learn one boolean the
     * platform already knows is a build change for nothing.
     */
    private fun conditions(soakMinutes: Int): RunConditions {
        val battery =
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else 0

        val version =
            runCatching {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                "${info.versionName} (${info.longVersionCode})"
            }.getOrDefault("unknown")

        val debuggable =
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        return RunConditions(
            // MANUFACTURER as well as MODEL: "M2101K6G" names nothing to a reader, and the
            // preamble exists to be read by somebody who was not in the room.
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            build = version,
            soakMinutes = soakMinutes,
            batteryPercent = percent,
            onCharge = plugged != 0,
            date = LocalDate.now().toString(),
            isReleaseBuild = !debuggable,
        )
    }

    /**
     * Puts one file where the operator can actually reach it.
     *
     * `Android/data/` has not been browsable by a file manager since Android 11, so the
     * app's own external directory is the wrong destination for something a human is meant
     * to collect — the same lesson `PackInstaller` records from the other direction.
     * MediaStore's Downloads collection is reachable; if it refuses, the external files
     * directory is still better than nothing and the returned path says which was used.
     *
     * @return the human-readable location, or null if neither worked.
     */
    private fun write(
        name: String,
        body: String,
    ): String? {
        val viaStore =
            runCatching {
                val values =
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/iTantra")
                    }
                val uri =
                    context.contentResolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        values,
                    ) ?: return@runCatching null
                context.contentResolver.openOutputStream(uri)?.use { it.write(body.toByteArray()) }
                "Download/iTantra"
            }.getOrNull()
        if (viaStore != null) return viaStore

        return runCatching {
            val dir = File(context.getExternalFilesDir(null), "reports").apply { mkdirs() }
            File(dir, name).writeText(body)
            dir.absolutePath
        }.getOrNull()
    }
}
