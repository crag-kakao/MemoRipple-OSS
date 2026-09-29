package io.github.cragcoffee.memoripple.llmbench

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Started by tools/llm-eval/scripts/run-bench.sh with extras:
 *   --es model <file under files/models or absolute path>  --es label <run label>
 *   --es workload smoke|accuracy|performance|battery|lifecycle  [--ei limit N]
 *   [--ei nCtx 4096] [--ei nBatch 512] [--ei nThreads 4] [--ei maxTokens 256]
 *   [--es assistantPrefix <text>] [--ez noMmap true] [--es inputDir /data/local/tmp/llmbench]
 * Launched from the launcher without extras it only shows what it found on disk.
 */
class BenchActivity : Activity() {
    private lateinit var text: TextView
    private var runner: BenchRunner? = null
    private var thread: Thread? = null
    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        text = TextView(this).apply { textSize = 11f; setPadding(24, 48, 24, 48) }
        setContentView(ScrollView(this).apply { addView(text) })
        start(intent)
    }

    /** singleTask: a second `am start` while the activity exists lands here; a new run begins once the previous one has finished. */
    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        if (intent != null) start(intent)
    }

    private fun start(intent: android.content.Intent) {
        val model = intent.getStringExtra("model")
        val root = getExternalFilesDir(null)
        if (model == null) {
            val models = File("/data/local/tmp/llmbench/models").listFiles()?.map { "${it.name} ${it.length() / 1024 / 1024}MB" } ?: emptyList()
            append("MemoRipple LLM Bench (Phase 0)\nresults: $root\nmodels: $models\nstart with am start … --es model <file> --es label <label> --es workload smoke")
            return
        }
        if (thread?.isAlive == true) { append("a run is still in progress; ignoring the new start"); return }
        val config = BenchRunner.Config(
            modelFile = model,
            label = intent.getStringExtra("label") ?: File(model).nameWithoutExtension,
            workload = intent.getStringExtra("workload") ?: "smoke",
            limit = intent.getIntExtra("limit", 0).let { when { it != 0 -> it; intent.getStringExtra("workload") == "smoke" -> 5; intent.getStringExtra("workload") == "performance" -> 15; else -> 0 } },
            nCtx = intent.getIntExtra("nCtx", 4096),
            nBatch = intent.getIntExtra("nBatch", 512),
            nThreads = intent.getIntExtra("nThreads", 4),
            maxTokens = intent.getIntExtra("maxTokens", 256),
            useMmap = !intent.getBooleanExtra("noMmap", false),
            assistantPrefix = intent.getStringExtra("assistantPrefix") ?: "",
            lifecycleCycles = intent.getIntExtra("cycles", 3),
            idleSeconds = intent.getIntExtra("idleSeconds", 30),
            lifecycleFirstCases = intent.getIntExtra("lifecycleFirstCases", 5),
            lifecycleReloadCases = intent.getIntExtra("lifecycleReloadCases", 2),
            inputDir = intent.getStringExtra("inputDir") ?: "/data/local/tmp/llmbench",
            promptVersion = intent.getStringExtra("promptVersion") ?: "v2",
            subset = intent.getStringExtra("subset") ?: "",
            batteryMinutes = intent.getIntExtra("batteryMinutes", 10),
        )
        append("config: $config")
        val r = BenchRunner(this, config) { line -> runOnUiThread { append(line) } }
        runner = r
        thread = Thread({ r.run() }, "llmbench").also { it.start() }
    }

    override fun onDestroy() {
        runner?.cancelled = true
        super.onDestroy()
    }

    private fun append(line: String) {
        text.append("${stamp.format(Date())} $line\n")
        (text.parent as? ScrollView)?.post { (text.parent as ScrollView).fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
