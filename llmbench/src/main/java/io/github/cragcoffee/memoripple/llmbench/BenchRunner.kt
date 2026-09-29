package io.github.cragcoffee.memoripple.llmbench

import android.content.Context
import java.io.File
import java.text.Normalizer
import org.json.JSONArray
import org.json.JSONObject

/**
 * Runs one workload for one model and writes results (docs/LLM_PHASE0.md §6–§7).
 *
 * Inputs live under /data/local/tmp/llmbench (pushed by tools/llm-eval/scripts/run-bench.sh):
 * dataset (golden.jsonl), grammar (gbnf), templates (json), prompts (txt), models (gguf).
 * Outputs: results/<label>-<workload>.jsonl (one line per case) and
 * results/<label>-<workload>.metrics.json (run header, snapshots, summary); a
 * results/<label>-<workload>.done marker closes the run so the host can wait on it.
 */
class BenchRunner(
    private val context: Context,
    private val config: Config,
    private val log: (String) -> Unit,
) {
    data class Config(
        val modelFile: String,
        val label: String,
        val workload: String,
        val limit: Int,
        val nCtx: Int = 4096,
        val nBatch: Int = 512,
        val nThreads: Int = 4,
        val maxTokens: Int = 256,
        val useMmap: Boolean = true,
        /** Text appended after the templated prompt (e.g. an empty think block for models whose template opens one). */
        val assistantPrefix: String = "",
        val lifecycleCycles: Int = 3,
        /** Generations in the first lifecycle cycle (the "5 consecutive generations" check) and in each reload cycle. */
        val lifecycleFirstCases: Int = 5,
        val lifecycleReloadCases: Int = 2,
        val idleSeconds: Int = 30,
        /** Where the host pushed dataset, grammar, templates, prompts and models: a directory the app can read
         *  (files under the app's own external dir pushed by adb belong to the shell user and are not). */
        val inputDir: String = "/data/local/tmp/llmbench",
        /** prompts/<promptVersion>/ (v1 = the round-1 prompts kept as run; v2 = the one permitted common revision). */
        val promptVersion: String = "v2",
        /** Optional id list under dataset/ that overrides the workload's default subset (accuracy / performance / battery). */
        val subset: String = "",
        /** battery workload: run the fixed sequence for at most this many minutes (or until SEVERE). */
        val batteryMinutes: Int = 10,
    )

    private data class Case(
        val id: String,
        val category: String,
        val input: String,
        val results: List<String>,
        val template: String?,
        val raw: JSONObject,
    ) {
        val mode: String get() = if (category == "template" || category == "hallucination") "template" else "intent"
    }

    private data class Template(val id: String, val name: String, val fields: List<Field>)
    private data class Field(val key: String, val label: String, val required: Boolean)

    /** Results go to the app's own external files dir (the app creates it, adb can pull from it). */
    private val root: File = context.getExternalFilesDir(null) ?: context.filesDir
    private val inputs: File = File(config.inputDir)
    private val metrics = Metrics(context)
    private val snapshots = JSONArray()
    private val bridge = LlamaBridge()
    @Volatile var cancelled = false
    private var promptSample: String? = null

    fun run() {
        val started = System.currentTimeMillis()
        val resultsDir = File(root, "results").also { it.mkdirs() }
        val base = "${config.label}-${config.workload}"
        val casesOut = File(resultsDir, "$base.jsonl")
        val metricsOut = File(resultsDir, "$base.metrics.json")
        val doneMarker = File(resultsDir, "$base.done")
        doneMarker.delete()
        casesOut.delete()

        val header = JSONObject()
            .put("label", config.label)
            .put("workload", config.workload)
            .put("modelFile", config.modelFile)
            .put("modelSizeBytes", modelPath().length())
            .put("nCtx", config.nCtx).put("nBatch", config.nBatch).put("nThreads", config.nThreads)
            .put("maxTokens", config.maxTokens).put("temperature", 0).put("useMmap", config.useMmap)
            .put("assistantPrefix", config.assistantPrefix).put("promptVersion", config.promptVersion).put("executor", "s20")
            .put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (${android.os.Build.DEVICE}) API ${android.os.Build.VERSION.SDK_INT}")
            .put("llamaSystemInfo", bridge.systemInfo())
            .put("startedAt", started)
        val summary = JSONObject()
        try {
            if (config.workload == "battery") waitForUntetheredAndCool()
            if (config.workload == "performance") waitForThermalZero()
            val startSnap = snap("before_load")
            val chargeStart = metrics.chargeCounterMicroAh()
            summary.put("startBatteryPercent", startSnap.batteryPercent).put("startBatteryTempDeciC", startSnap.batteryTempDeciC).put("startPlugged", startSnap.batteryPlugged)
            when (config.workload) {
                "lifecycle" -> lifecycle(casesOut, summary)
                else -> {
                    val cases = selectCases()
                    log("cases: ${cases.size} (${config.workload})")
                    val handle = load(header) ?: return
                    try {
                        runCases(handle, cases, casesOut, summary)
                    } finally {
                        unload(handle)
                    }
                }
            }
            val chargeEnd = metrics.chargeCounterMicroAh()
            if (chargeStart != null && chargeEnd != null) summary.put("chargeDeltaMicroAh", chargeStart - chargeEnd)
            val endSnap = snap("end")
            summary.put("totalMs", System.currentTimeMillis() - started)
                .put("endBatteryPercent", endSnap.batteryPercent).put("endBatteryTempDeciC", endSnap.batteryTempDeciC).put("endPlugged", endSnap.batteryPlugged)
                .put("batteryDeltaPercent", startSnap.batteryPercent - endSnap.batteryPercent)
            val minutes = summary.optLong("runMs", 0L) / 60_000.0
            val intents = summary.optInt("completedIntents", 0)
            if (minutes > 0) summary.put("batteryDeltaPerMinute", (startSnap.batteryPercent - endSnap.batteryPercent) / minutes)
            if (intents > 0) summary.put("batteryDeltaPerIntent", (startSnap.batteryPercent - endSnap.batteryPercent).toDouble() / intents)
            val peakTemp = (0 until snapshots.length()).maxOfOrNull { snapshots.getJSONObject(it).optInt("batteryTempDeciC") } ?: 0
            summary.put("peakBatteryTempDeciC", peakTemp)
            if (config.workload == "battery") coolDown(summary)
        } catch (t: Throwable) {
            log("FAILED: $t")
            summary.put("error", t.toString())
        } finally {
            header.put("snapshots", snapshots).put("summary", summary)
            header.put("promptSample", promptSample ?: JSONObject.NULL)
            metricsOut.writeText(header.toString(2))
            doneMarker.writeText(if (summary.has("error")) "error" else "ok")
            log("done → ${casesOut.name}, ${metricsOut.name}")
        }
    }

    private fun modelPath(): File {
        val f = File(config.modelFile)
        return if (f.isAbsolute) f else File(inputs, "models/${config.modelFile}")
    }

    private fun load(header: JSONObject?): LlamaBridge.Handle? {
        val path = modelPath()
        if (!path.exists()) {
            log("model missing: $path")
            return null
        }
        log("loading ${path.name} (${path.length() / 1024 / 1024} MB)")
        val handle = bridge.load(path.absolutePath, config.nCtx, config.nBatch, config.nThreads, config.useMmap)
        if (handle == null) {
            log("load failed (see logcat llmbench)")
            return null
        }
        val info = bridge.modelInfo(handle)
        log("loaded in ${info.loadMs} ms: ${info.desc}; template=${info.hasTemplate} jinja=${info.jinja} thinking=${info.supportsThinking}; nCtxTrain=${info.nCtxTrain}")
        header?.put("model", JSONObject()
            .put("desc", info.desc).put("sizeBytes", info.sizeBytes).put("nParams", info.nParams)
            .put("hasTemplate", info.hasTemplate).put("jinja", info.jinja).put("supportsThinking", info.supportsThinking).put("bosText", info.bosText).put("nCtxTrain", info.nCtxTrain).put("nCtx", info.nCtx)
            .put("loadMs", info.loadMs).put("chatTemplate", bridge.chatTemplate(handle)))
        snap("after_load")
        return handle
    }

    private fun unload(handle: LlamaBridge.Handle) {
        bridge.unload(handle)
        snap("after_unload")
    }

    private fun snap(label: String): Snapshot {
        val s = metrics.snapshot(label)
        snapshots.put(s.toJson())
        log("[$label] rss=${s.vmRssKb / 1024}MB hwm=${s.vmHwmKb / 1024}MB avail=${s.deviceAvailKb / 1024}MB bat=${s.batteryPercent}% ${s.batteryTempDeciC / 10.0}°C thermal=${s.thermalStatus}")
        return s
    }

    // ---- workloads -------------------------------------------------------------------------

    private fun selectCases(): List<Case> {
        val all = loadCases()
        if (config.workload == "smoke") {
            val smoke = File(inputs, "dataset/smoke_subset.jsonl")
            if (smoke.exists()) return loadCases(smoke).let { if (config.limit > 0) it.take(config.limit) else it }
        }
        val subsetFile = if (config.subset.isNotEmpty()) File(inputs, "dataset/${config.subset}") else when (config.workload) {
            "performance" -> File(inputs, "dataset/performance_subset.txt")
            "battery" -> File(inputs, "dataset/battery_subset.txt")
            else -> null
        }
        val selected0 = if (subsetFile != null && subsetFile.exists()) {
            val ids = subsetFile.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            val byId = all.associateBy { it.id }
            ids.mapNotNull { byId[it] }
        } else all
        // battery: the same sequence repeated until the time window closes (the runner stops it)
        val selected = if (config.workload == "battery") List(6) { selected0 }.flatten() else selected0
        return if (config.limit > 0) selected.take(config.limit) else selected
    }

    private var thermalStops = 0
    private var thermalEnded = false
    private var runStartWall = 0L

    private fun runCases(handle: LlamaBridge.Handle, cases: List<Case>, out: File, summary: JSONObject) {
        runStartWall = System.currentTimeMillis()
        val templates = loadTemplates()
        val intentSystem = File(inputs, "prompts/${config.promptVersion}/intent_system.txt").readText().trim()
        val templateSystem = File(inputs, "prompts/${config.promptVersion}/template_system.txt").readText().trim()
        val intentGrammar = File(inputs, "grammar/intent_proposal.gbnf").readText()
        val templateGrammarTemplate = File(inputs, "grammar/template_fields.gbnf.template").readText()
        var n = 0; var ok = 0; var grammarOk = 0; var jsonOk = 0
        val ttfts = ArrayList<Double>(); val tpss = ArrayList<Double>(); var peakRss = 0L
        val runStart = System.currentTimeMillis()
        var pauses = 0
        val benchmark = config.workload == "battery" || config.workload == "performance"
        val redo = ArrayList<Case>()
        val queue = ArrayDeque(cases)
        val ttftAll = ArrayList<Double>(); val tpsByThermal = HashMap<Int, ArrayList<Double>>()
        val deadline = if (config.workload == "battery") runStartWall + config.batteryMinutes * 60_000L else Long.MAX_VALUE
        var completedIntents = 0
        while (queue.isNotEmpty() || redo.isNotEmpty()) {
            if (queue.isEmpty()) { queue.addAll(redo); redo.clear() }
            val case = queue.removeFirst()
            if (config.workload == "battery" && System.currentTimeMillis() >= deadline) { log("battery window over"); break }
            if (cancelled) { log("cancelled"); break }
            // Thermal guard (docs/LLM_PHASE0.md §8): never run a case at SEVERE or worse — pause until the
            // status is back to ≤ 1 (light) or 20 min pass; each pause is recorded in the summary.
            var waited = 0
            while (metrics.snapshot("thermal_check").thermalStatus >= 3 && waited < 20 * 60 && !cancelled) {
                if (waited == 0) { pauses++; log("thermal ≥ SEVERE — pausing"); snap("thermal_pause") }
                Thread.sleep(15_000); waited += 15
            }
            if (waited > 0) { log("resumed after ${waited}s"); snap("thermal_resume") }
            val (system, user, grammar) = when (case.mode) {
                "template" -> {
                    val t = templates[case.template ?: "daily_journal"] ?: error("unknown template ${case.template}")
                    Triple(renderTemplateSystem(templateSystem, t), "入力: ${case.input}", renderTemplateGrammar(templateGrammarTemplate, t))
                }
                else -> Triple(intentSystem, renderIntentUser(case), intentGrammar)
            }
            val prompt = bridge.applyChatTemplate(handle, system, user) + config.assistantPrefix
            if (n == 0) promptSample = prompt
            // Thermal watcher (2 s): at SEVERE or worse the running generation is cancelled at the next token.
            val severe = java.util.concurrent.atomic.AtomicBoolean(false)
            val watcher = Thread {
                try {
                    while (!Thread.currentThread().isInterrupted) {
                        Thread.sleep(2_000)
                        if (metrics.thermalStatus() >= 3) { severe.set(true); bridge.requestStop(handle); break }
                    }
                } catch (_: InterruptedException) { }
            }.also { it.isDaemon = true; it.start() }
            val g = bridge.generate(handle, prompt, grammar, config.maxTokens)
            watcher.interrupt()
            if (severe.get() || g.stop == "cancelled") {
                thermalStops++
                snap("thermal_stop")
                if (benchmark) { log("thermal SEVERE during ${case.id} — run ended (benchmark rule)"); thermalEnded = true; break }
                log("thermal SEVERE during ${case.id} — cancelled; cooling, then the case is redone")
                var w = 0
                while (metrics.thermalStatus() > 1 && w < 20 * 60 && !cancelled) { Thread.sleep(15_000); w += 15 }
                snap("thermal_resume")
                redo.add(case)
                continue
            }
            val parsed = tryParseJson(g.text)
            val thermalNow = metrics.snapshot("case")
            val rss = thermalNow.vmRssKb
            if (rss > peakRss) peakRss = rss
            if (g.ok) { ttftAll.add(g.ttftMs); if (g.tokensPerSecond > 0) tpsByThermal.getOrPut(thermalNow.thermalStatus) { ArrayList() }.add(g.tokensPerSecond) }
            if (g.ok && g.stop == "eog") completedIntents++
            val rec = JSONObject()
                .put("id", case.id).put("category", case.category).put("mode", case.mode)
                .put("label", config.label).put("modelFile", config.modelFile).put("promptVersion", config.promptVersion)
                .put("ok", g.ok).put("stop", g.stop).put("grammarUsed", g.grammarUsed)
                .put("output", g.text)
                .put("jsonValid", parsed != null)
                .put("promptTokens", g.promptTokens).put("genTokens", g.genTokens)
                .put("promptMs", g.promptMs).put("ttftMs", g.ttftMs).put("genMs", g.genMs)
                .put("tokensPerSecond", g.tokensPerSecond)
                .put("rssKb", rss).put("thermalStatus", thermalNow.thermalStatus)
                .put("batteryPercent", thermalNow.batteryPercent).put("batteryTempDeciC", thermalNow.batteryTempDeciC)
                .put("elapsedMs", System.currentTimeMillis() - runStart)
            if (g.error != null) rec.put("error", g.error)
            out.appendText(rec.toString() + "\n")
            n++
            if (g.ok) ok++
            if (g.ok && g.stop == "eog") grammarOk++
            if (parsed != null) jsonOk++
            if (g.ok) { ttfts.add(g.ttftMs); if (g.tokensPerSecond > 0) tpss.add(g.tokensPerSecond) }
            log("${case.id} ${g.stop} ${g.genTokens}tok ttft=${g.ttftMs.toInt()}ms ${"%.1f".format(g.tokensPerSecond)}t/s json=${parsed != null} ${g.text.take(60).replace('\n', ' ')}")
        }
        snap("after_run")
        summary.put("cases", n).put("ok", ok).put("stoppedAtEog", grammarOk).put("jsonValid", jsonOk)
            .put("ttftMsMedian", median(ttfts)).put("ttftMsMean", ttfts.average().orZero())
            .put("tokensPerSecondMedian", median(tpss)).put("tokensPerSecondMean", tpss.average().orZero())
            .put("peakRssKb", peakRss).put("runMs", System.currentTimeMillis() - runStart).put("thermalPauses", pauses)
            .put("thermalStops", thermalStops).put("thermalEnded", thermalEnded).put("completedIntents", completedIntents)
            .put("ttftMsP90", percentile(ttftAll, 0.9))
            .put("tokensPerSecondByThermal", JSONObject().also { j -> tpsByThermal.toSortedMap().forEach { (k, v) -> j.put(k.toString(), median(v)) } })
    }

    private fun lifecycle(out: File, summary: JSONObject) {
        val smoke = File(inputs, "dataset/smoke_subset.jsonl")
        val pool = (if (smoke.exists()) loadCases(smoke) else loadCases()).filter { it.mode == "intent" }
        val cycles = JSONArray()
        for (cycle in 1..config.lifecycleCycles) {
            if (cancelled) break
            log("lifecycle cycle $cycle/${config.lifecycleCycles}")
            val h = load(null) ?: return
            val c = JSONObject().put("cycle", cycle)
            c.put("loadMs", bridge.modelInfo(h).loadMs)
            val sub = JSONObject()
            runCases(h, pool.take(if (cycle == 1) config.lifecycleFirstCases else config.lifecycleReloadCases), out, sub)
            c.put("gen", sub)
            unload(h)
            c.put("afterUnloadRssKb", snapshots.getJSONObject(snapshots.length() - 1).getLong("vmRssKb"))
            log("idle ${config.idleSeconds}s")
            Thread.sleep(config.idleSeconds * 1000L)
            c.put("afterIdleRssKb", snap("after_idle").vmRssKb)
            cycles.put(c)
        }
        summary.put("cycles", cycles)
    }

    /** Battery protocol (docs/LLM_PHASE0.md §8): the run begins only unplugged and at thermal 0; waits up to 30 min for each. */
    private fun waitForUntetheredAndCool() {
        var w = 0
        while (metrics.snapshot("wait").batteryPlugged && w < 30 * 60 && !cancelled) { if (w % 30 == 0) log("battery run: unplug USB (waiting ${w}s)"); Thread.sleep(5_000); w += 5 }
        if (metrics.snapshot("wait").batteryPlugged) { log("still plugged — battery run refused"); throw IllegalStateException("battery run needs the device unplugged") }
        waitForThermalZero()
    }

    private fun waitForThermalZero() {
        var w = 0
        while (metrics.thermalStatus() > 0 && w < 30 * 60 && !cancelled) { if (w % 30 == 0) log("waiting for thermal 0 (now ${metrics.thermalStatus()}, ${w}s)"); Thread.sleep(5_000); w += 5 }
        snap("start_condition")
    }

    private fun coolDown(summary: JSONObject) {
        // Battery workload: after the run, wait until the thermal status returns to 0 (or 10 min).
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 10 * 60 * 1000L) {
            val s = snap("cooldown")
            if (s.thermalStatus == 0) break
            Thread.sleep(30_000)
        }
        summary.put("coolDownMs", System.currentTimeMillis() - t0)
    }

    // ---- prompts ---------------------------------------------------------------------------

    private fun renderIntentUser(case: Case): String {
        val sb = StringBuilder()
        sb.append("表示中の候補:\n")
        if (case.results.isEmpty()) sb.append("(なし)\n") else case.results.forEach { sb.append(it).append('\n') }
        sb.append("入力: ").append(case.input)
        return sb.toString()
    }

    private fun renderTemplateSystem(template: String, t: Template): String =
        template.replace("{{TEMPLATE_NAME}}", t.name).replace("{{TEMPLATE_ID}}", t.id)
            .replace("{{FIELD_LIST}}", t.fields.joinToString("\n") { "- ${it.key}: ${it.label} / ${if (it.required) "必須" else "任意"}" })

    private fun renderTemplateGrammar(template: String, t: Template): String =
        template.replace("{{TEMPLATE_ID}}", t.id)
            .replace("{{FIELDS}}", t.fields.joinToString(" \",\" ws ") { "\"\\\"${it.key}\\\":\" ws strornull" })

    // ---- inputs ----------------------------------------------------------------------------

    private fun loadCases(file: File = File(inputs, "dataset/golden.jsonl")): List<Case> =
        file.readLines().filter { it.isNotBlank() }.map { line ->
            val j = JSONObject(line)
            val ctx = j.optJSONObject("context")
            val results = ArrayList<String>()
            ctx?.optJSONArray("results")?.let { arr -> for (i in 0 until arr.length()) results.add(arr.getString(i)) }
            Case(
                id = j.getString("id"), category = j.getString("category"), input = j.getString("input"),
                results = results, template = ctx?.optString("template", null)?.takeIf { it.isNotEmpty() }, raw = j,
            )
        }

    private fun loadTemplates(): Map<String, Template> {
        val dir = File(inputs, "templates")
        return (dir.listFiles() ?: emptyArray()).filter { it.name.endsWith(".json") }.map { f ->
            val j = JSONObject(f.readText())
            val fields = j.getJSONArray("fields")
            Template(
                id = j.getString("id"), name = j.optString("name", j.getString("id")),
                fields = (0 until fields.length()).map { i ->
                    val fj = fields.getJSONObject(i)
                    Field(fj.getString("key"), fj.optString("label", fj.getString("key")), fj.optBoolean("required", false))
                },
            )
        }.associateBy { it.id }
    }

    private fun tryParseJson(text: String): JSONObject? = try {
        JSONObject(Normalizer.normalize(text, Normalizer.Form.NFC))
    } catch (_: Throwable) {
        null
    }

    private fun percentile(v: List<Double>, p: Double): Double {
        if (v.isEmpty()) return 0.0
        val s = v.sorted()
        return s[minOf(s.size - 1, kotlin.math.ceil(p * s.size).toInt() - 1).coerceAtLeast(0)]
    }

    private fun median(v: List<Double>): Double {
        if (v.isEmpty()) return 0.0
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    private fun Double.orZero(): Double = if (isNaN()) 0.0 else this
}
