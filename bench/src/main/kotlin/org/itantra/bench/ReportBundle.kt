package org.itantra.bench

/**
 * The three result files, and the conditions under which they may be written. Tasks
 * **W8.8** and **W8.2**.
 *
 * ## Why the conditions are code and not a checklist
 *
 * `docs/EVALUATION.md` section 1 says figures produced outside its conditions "are not
 * reportable". That sentence has no force while the conditions live in a document and the
 * CSV writer takes whatever it is handed. The failure mode is specific and it is the one
 * that happens: somebody runs the benchmark on a debug build at eleven at night, the file
 * is generated, it is committed, and three weeks later a number from it is on a slide with
 * nothing to say it came from a cold flagship on charge.
 *
 * So the conditions are checked here, once, before anything is written. A run that does not
 * meet them produces **no files** rather than files that need a caveat nobody will attach.
 *
 * ## Why the metadata is in the file rather than the filename
 *
 * `latency.csv` says nothing about where it came from. `latency-pixel4a-30min.csv` says
 * something until somebody renames it, which they will. Every file this writes carries a
 * commented preamble naming the device, the build, the soak duration and the date — the
 * four facts a reader needs to know whether to believe the rows underneath.
 *
 * The preamble is `#`-commented so a spreadsheet skips it and a human does not.
 */
class ReportBundle(val conditions: RunConditions) {
    /**
     * @return the three files by name, or throws if [conditions] are not reportable. The
     *   caller has already been told why by [RunConditions.unmetRequirements].
     */
    fun write(
        traces: List<UtteranceTrace>,
        samples: List<ResourceSample>,
        scorecard: List<ScorecardRow>,
    ): Map<String, String> {
        val unmet = conditions.unmetRequirements(traces)
        require(unmet.isEmpty()) {
            "not reportable under EVALUATION.md section 1:\n" + unmet.joinToString("\n") { "  - $it" }
        }

        return mapOf(
            "latency.csv" to
                csv(LatencyLog.COLUMNS) { sink ->
                    val log = LatencyLog(sink)
                    traces.forEach(log::write)
                },
            "resource.csv" to
                csv(ResourceLogWriter.COLUMNS) { sink ->
                    val writer = ResourceLogWriter(sink)
                    samples.forEach(writer::write)
                },
            // The scorecard already carries device, build and soak as columns on every
            // row, because a scorecard row travels alone into a table. The preamble is
            // repeated anyway: two statements of the same fact that must agree is a
            // cheaper defence than one that might be dropped.
            "scorecard.csv" to
                csv(ScorecardWriter.COLUMNS) { sink ->
                    val writer = ScorecardWriter(sink)
                    scorecard.forEach(writer::write)
                },
        )
    }

    private fun csv(
        columns: List<String>,
        body: (StringBuilder) -> Unit,
    ): String {
        val out = StringBuilder()
        out.append(conditions.preamble())
        out.append(columns.joinToString(",")).append('\n')
        body(out)
        return out.toString()
    }
}

/**
 * What a measurement run was, and whether it counts.
 *
 * @param device the handset, never "emulator" and never a flagship
 * @param build the APK identity — a release build, and the same one the demonstration uses
 * @param soakMinutes minutes of continuous inference before the first figure was taken
 * @param batteryPercent battery at the start of the run
 * @param onCharge whether the handset was charging; many throttle differently on charge
 */
data class RunConditions(
    val device: String,
    val build: String,
    val soakMinutes: Int,
    val batteryPercent: Int,
    val onCharge: Boolean,
    val date: String,
    val isReleaseBuild: Boolean,
) {
    /**
     * Every condition from `docs/EVALUATION.md` section 1 this run fails.
     *
     * All of them, not the first — a run being told it failed on the build only to be told
     * it also failed on the battery is two wasted half-hours.
     */
    fun unmetRequirements(traces: List<UtteranceTrace> = emptyList()): List<String> {
        val unmet = ArrayList<String>()

        if (device.isBlank()) unmet += "no device named"
        if (device.lowercase().contains("emulator")) {
            unmet += "'$device' is an emulator; audio and thermal figures from one mean nothing"
        }
        if (build.isBlank()) unmet += "no build named"
        if (!isReleaseBuild) {
            unmet += "not a release build; a debug build is not the APK the demonstration runs"
        }
        if (soakMinutes < REQUIRED_SOAK_MINUTES) {
            unmet +=
                "soaked $soakMinutes minutes, and the requirement is $REQUIRED_SOAK_MINUTES; " +
                "an entry-tier handset throttles after about ten and cold figures are not reportable"
        }
        if (batteryPercent <= MINIMUM_BATTERY_PERCENT) {
            unmet += "battery at $batteryPercent %, and the requirement is above $MINIMUM_BATTERY_PERCENT %"
        }
        if (onCharge) unmet += "measured on charge; many handsets throttle differently charging"
        if (date.isBlank()) unmet += "no date"

        // Only checked when traces are supplied: the resource CSV has no utterance count.
        if (traces.isNotEmpty() && !LatencySummary.isReportable(traces)) {
            val completed = traces.count { it.endToEndMillis != null }
            unmet +=
                "$completed utterances completed, and the requirement is " +
                "${LatencySummary.MINIMUM_UTTERANCES}; a median over fewer is not a median"
        }
        return unmet
    }

    val isReportable: Boolean get() = unmetRequirements().isEmpty()

    /** The four facts a reader needs before believing the rows underneath. */
    fun preamble(): String =
        buildString {
            append("# device: ").append(device).append('\n')
            append("# build: ").append(build).append(if (isReleaseBuild) " (release)" else " (DEBUG)").append('\n')
            append("# soak_minutes: ").append(soakMinutes).append('\n')
            append("# battery_pct: ").append(batteryPercent)
                .append(if (onCharge) " (ON CHARGE)" else "").append('\n')
            append("# date: ").append(date).append('\n')
        }

    companion object {
        const val REQUIRED_SOAK_MINUTES = 30
        const val MINIMUM_BATTERY_PERCENT = 30
    }
}
