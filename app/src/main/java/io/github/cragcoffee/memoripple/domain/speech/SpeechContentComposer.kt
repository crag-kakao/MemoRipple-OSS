package io.github.cragcoffee.memoripple.domain.speech

import io.github.cragcoffee.memoripple.data.MemoCommentEntity
import io.github.cragcoffee.memoripple.domain.diary.FutureCommentRevealContext

enum class FutureSpeechContent { SOURCE_DIARY, FUTURE_COMMENT, BOTH }

class SpeechContentComposer(
    private val preprocessor: SpeechTextPreprocessor = SpeechTextPreprocessor(),
    private val filter: SpeechContentFilter = SpeechContentFilter(),
) {
    fun memo(
        title: String,
        body: String,
        comments: List<MemoCommentEntity>,
        includeComments: Boolean,
        readRubyReadings: Boolean = false,
    ): String = buildList {
        title.trim().takeIf(String::isNotBlank)?.let(::add)
        preprocessor.preprocessBody(body, readRubyReadings).takeIf(String::isNotBlank)?.let(::add)
        if (includeComments) {
            // Linked comments belong to their markers, not to the read-through list.
            comments.filter { it.linkNo == null }
                .sortedWith(compareBy(MemoCommentEntity::playbackOrder, MemoCommentEntity::id))
                .map { it.text.trim() }
                .filter(String::isNotBlank)
                .filter(filter::shouldSpeak)
                .forEach(::add)
        }
    }.joinToString(PART_SEPARATOR)

    fun diary(body: String, readRubyReadings: Boolean = false): String =
        preprocessor.preprocessBody(body, readRubyReadings)

    fun future(
        context: FutureCommentRevealContext,
        content: FutureSpeechContent,
        readRubyReadings: Boolean = false,
    ): String {
        if (context.comment.firstPresentedAt == null) return ""
        return buildList {
            if (content == FutureSpeechContent.SOURCE_DIARY || content == FutureSpeechContent.BOTH) {
                preprocessor.preprocessBody(context.sourceDiary.body, readRubyReadings)
                    .takeIf(String::isNotBlank)?.let(::add)
            }
            if (content == FutureSpeechContent.FUTURE_COMMENT || content == FutureSpeechContent.BOTH) {
                context.comment.text.trim().takeIf(String::isNotBlank)?.let(::add)
            }
        }.joinToString(PART_SEPARATOR)
    }

    companion object {
        const val PART_SEPARATOR = "\n\n"
    }
}
