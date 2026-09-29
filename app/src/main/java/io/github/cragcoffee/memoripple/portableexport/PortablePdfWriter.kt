package io.github.cragcoffee.memoripple.portableexport

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import io.github.cragcoffee.memoripple.data.AttachmentBlobStore
import io.github.cragcoffee.memoripple.domain.export.PortableExportPlanner.PlannedPhoto
import io.github.cragcoffee.memoripple.domain.export.PortableExportPlanner.PortableExportPlan
import io.github.cragcoffee.memoripple.domain.export.PortableMarkdownRenderer
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * ひとつのPDF — the whole export as pages a printer understands, drawn with the platform's
 * [PdfDocument] and [StaticLayout] (no new dependency; Japanese wrapping is the platform's
 * own). Records flow one after another with page breaks where they run out of room; photos
 * are decoded one at a time, downsampled to page size, and recycled — the original blobs
 * are read-only throughout, and since every image is redrawn, camera metadata never enters
 * the PDF at all.
 */
class PortablePdfWriter(
    private val blobStore: AttachmentBlobStore,
    private val zone: ZoneId,
) {

    private val pageWidth = 595 // A4 @72dpi
    private val pageHeight = 842
    private val margin = 44f
    private val contentWidth = (pageWidth - margin * 2).toInt()

    private val titlePaint = TextPaint().apply {
        isAntiAlias = true
        textSize = 17f
        isFakeBoldText = true
        color = Color.BLACK
    }
    private val metaPaint = TextPaint().apply {
        isAntiAlias = true
        textSize = 10f
        color = 0xFF666666.toInt()
    }
    private val bodyPaint = TextPaint().apply {
        isAntiAlias = true
        textSize = 11.5f
        color = Color.BLACK
    }
    private val rulePaint = Paint().apply {
        color = 0xFFCCCCCC.toInt()
        strokeWidth = 0.7f
    }

    private lateinit var document: PdfDocument
    private var page: PdfDocument.Page? = null
    private var cursorY = 0f
    private var pageNumber = 0

    /** Renders the whole [plan] into [target]; [onRecord] ticks once per finished document. */
    fun write(
        plan: PortableExportPlan,
        exportedAtMillis: Long,
        target: File,
        isActive: () -> Boolean,
        onRecord: () -> Unit,
    ) {
        document = PdfDocument()
        pageNumber = 0
        page = null
        try {
            newPage()
            drawText("MemoRipple 書き出し", titlePaint)
            drawText(
                Instant.ofEpochMilli(exportedAtMillis).atZone(zone)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) +
                    " ／ このPDFはMemoRippleへの復元用バックアップではありません。",
                metaPaint,
            )

            fun checkActive() {
                if (!isActive()) throw InterruptedException("cancelled")
            }

            for (planned in plan.allMemos) {
                checkActive()
                val memo = planned.memo
                startRecord(memo.title.ifBlank { "無題のメモ" })
                val meta = buildString {
                    append(stamp(memo.createdAt)).append(" 作成 ／ ")
                    append(stamp(memo.updatedAt)).append(" 更新")
                    if (memo.tags.isNotEmpty()) {
                        append(" ／ タグ: ").append(memo.tags.joinToString("、"))
                    }
                }
                drawText(meta, metaPaint)
                if (memo.body.isNotBlank()) drawText(memo.body, bodyPaint)
                drawPhotos(planned.photos)
                if (memo.comments.isNotEmpty()) {
                    drawText("コメント", titlePaint)
                    memo.comments.forEachIndexed { index, comment ->
                        val notes = if (comment.expressionNotes.isEmpty()) "" else {
                            "（" + comment.expressionNotes.joinToString("、") + "）"
                        }
                        drawText("${index + 1}. ${comment.text.replace('\n', ' ')}$notes", bodyPaint)
                    }
                }
                onRecord()
            }

            for (planned in plan.diaries) {
                checkActive()
                startRecord("${planned.dateLabel} の日記")
                drawText("状態: ${planned.diary.stateLabel}", metaPaint)
                if (planned.diary.body.isNotBlank()) drawText(planned.diary.body, bodyPaint)
                drawPhotos(planned.photos)
                if (planned.diary.futureComments.isNotEmpty()) {
                    drawText("開封済みの未来コメント", titlePaint)
                    planned.diary.futureComments.forEachIndexed { index, comment ->
                        drawText("${index + 1}. ${comment.text.replace('\n', ' ')}", bodyPaint)
                    }
                }
                onRecord()
            }

            for (planned in plan.notes) {
                checkActive()
                startRecord("ノート: " + planned.note.title.ifBlank { "無題のノート" })
                if (planned.note.subtitle.isNotBlank()) {
                    drawText(planned.note.subtitle, metaPaint)
                }
                planned.coverPhoto?.let { drawPhotos(listOf(it)) }
                onRecord()
                for (file in planned.episodeFiles) {
                    checkActive()
                    startRecord(
                        "第${file.episode.number}話 " + file.episode.title.ifBlank { "無題" },
                    )
                    file.sectionTitle?.let { drawText("章: $it", metaPaint) }
                    if (file.episode.body.isNotBlank()) drawText(file.episode.body, bodyPaint)
                    drawPhotos(file.photos)
                    onRecord()
                }
            }

            page?.let { document.finishPage(it) }
            page = null
            target.outputStream().use { document.writeTo(it) }
        } finally {
            page?.let { runCatching { document.finishPage(it) } }
            document.close()
        }
    }

    private fun stamp(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

    private fun newPage() {
        page?.let { document.finishPage(it) }
        pageNumber += 1
        page = document.startPage(
            PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create(),
        )
        cursorY = margin
    }

    private fun remaining(): Float = pageHeight - margin - cursorY

    /** A record begins under a rule, or on a fresh page when little room is left. */
    private fun startRecord(title: String) {
        if (remaining() < 120f) {
            newPage()
        } else if (cursorY > margin) {
            cursorY += 14f
            page!!.canvas.drawLine(margin, cursorY, pageWidth - margin, cursorY, rulePaint)
            cursorY += 14f
        }
        drawText(title, titlePaint)
    }

    /** Lays [text] out at content width and draws it across as many pages as its lines need. */
    private fun drawText(text: String, paint: TextPaint) {
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, contentWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(2f, 1f)
            .build()
        var line = 0
        while (line < layout.lineCount) {
            if (remaining() < layout.getLineBottom(line) - layout.getLineTop(line)) {
                newPage()
            }
            // How many whole lines fit on this page.
            val topOfRun = layout.getLineTop(line)
            var last = line
            while (last + 1 < layout.lineCount &&
                layout.getLineBottom(last + 1) - topOfRun <= remaining()
            ) {
                last += 1
            }
            val canvas = page!!.canvas
            canvas.save()
            canvas.translate(margin, cursorY - topOfRun)
            canvas.clipRect(
                0f,
                topOfRun.toFloat(),
                contentWidth.toFloat(),
                layout.getLineBottom(last).toFloat(),
            )
            layout.draw(canvas)
            canvas.restore()
            cursorY += layout.getLineBottom(last) - topOfRun + 6f
            line = last + 1
            if (line < layout.lineCount) newPage()
        }
    }

    /** One photo at a time: decode bounded, draw fit to width, recycle, move on. */
    private fun drawPhotos(photos: List<PlannedPhoto>) {
        photos.forEach { planned ->
            val file = runCatching { blobStore.blobFile(planned.photo.sha256) }.getOrNull()
            if (file == null || !file.isFile || file.length() != planned.photo.sizeBytes) {
                drawText(
                    PortableMarkdownRenderer.photoWarningLine(planned.displayIndex)
                        .removePrefix("> "),
                    metaPaint,
                )
                return@forEach
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            file.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                drawText(
                    PortableMarkdownRenderer.photoWarningLine(planned.displayIndex)
                        .removePrefix("> "),
                    metaPaint,
                )
                return@forEach
            }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= contentWidth * 2) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = file.inputStream().use { BitmapFactory.decodeStream(it, null, options) }
                ?: run {
                    drawText(
                        PortableMarkdownRenderer.photoWarningLine(planned.displayIndex)
                            .removePrefix("> "),
                        metaPaint,
                    )
                    return@forEach
                }
            try {
                val scale = minOf(
                    contentWidth.toFloat() / bitmap.width,
                    (pageHeight - margin * 2) / bitmap.height,
                    1f * contentWidth / bitmap.width, // never upscale beyond width fit
                )
                val drawWidth = bitmap.width * scale
                val drawHeight = bitmap.height * scale
                if (remaining() < drawHeight) newPage()
                val canvas: Canvas = page!!.canvas
                val left = margin
                canvas.drawBitmap(
                    bitmap,
                    null,
                    android.graphics.RectF(left, cursorY, left + drawWidth, cursorY + drawHeight),
                    null,
                )
                cursorY += drawHeight + 10f
            } finally {
                bitmap.recycle()
            }
        }
    }
}
