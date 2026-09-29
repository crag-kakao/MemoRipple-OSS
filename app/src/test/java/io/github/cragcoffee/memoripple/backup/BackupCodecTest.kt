package io.github.cragcoffee.memoripple.backup

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCodecTest {
    private val codec = BackupCodec()

    @Test
    fun jsonAndGzipRoundTripPreservesCompleteDocument() {
        val expected = fullBackupFixture()

        val decoded = codec.decode(codec.encode(expected))

        assertEquals(BackupDecodeResult.Success(expected), decoded)
    }

    @Test
    fun rejectsNonGzipInput() {
        assertEquals(
            BackupDecodeResult.Failure(BackupDecodeError.NOT_GZIP),
            codec.decode("not gzip".toByteArray()),
        )
    }

    @Test
    fun rejectsTruncatedGzip() {
        val encoded = codec.encode(fullBackupFixture())

        val result = codec.decode(encoded.copyOf(12))

        assertTrue(
            result == BackupDecodeResult.Failure(BackupDecodeError.INVALID_OR_TRUNCATED_GZIP) ||
                result == BackupDecodeResult.Failure(BackupDecodeError.INVALID_JSON),
        )
    }

    @Test
    fun rejectsGzipWhosePayloadIsNotJson() {
        val bytes = ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { it.write("not json".toByteArray()) }
            output.toByteArray()
        }

        assertEquals(
            BackupDecodeResult.Failure(BackupDecodeError.INVALID_JSON),
            codec.decode(bytes),
        )
    }

    @Test
    fun decodesVersionOneJsonWithoutOrganizationFieldsAsFalse() {
        val legacyJson = """
            {
              "format":"memorripple_backup",
              "formatVersion":1,
              "exportedAt":100,
              "appVersionName":"legacy",
              "appVersionCode":1,
              "payload":{
                "memos":[{"id":1,"title":"旧メモ","body":"本文","createdAt":10,"updatedAt":20}],
                "memoComments":[],
                "diaryEntries":[],
                "futureDiaryComments":[],
                "settings":{
                  "themeMode":"system","playbackSpeed":"standard",
                  "commentSize":"standard","stageBackground":"black"
                }
              }
            }
        """.trimIndent()
        val bytes = gzip(legacyJson)

        val document = (codec.decode(bytes) as BackupDecodeResult.Success).document
        val memo = document.payload.memos.single()

        assertEquals(1, document.formatVersion)
        assertEquals(false, memo.isFavorite)
        assertEquals(false, memo.isPinned)
        assertEquals(false, BackupMapper.toRoomSnapshot(document).memos.single().isFavorite)
    }

    @Test
    fun decodesVersionTwoJsonWithoutTagFieldsAsEmpty() {
        val legacyJson = """
            {
              "format":"memorripple_backup",
              "formatVersion":2,
              "exportedAt":100,
              "appVersionName":"legacy",
              "appVersionCode":1,
              "payload":{
                "memos":[{
                  "id":1,"title":"整理済み","body":"本文","createdAt":10,"updatedAt":20,
                  "isFavorite":true,"isPinned":true
                }],
                "memoComments":[],
                "diaryEntries":[],
                "futureDiaryComments":[],
                "settings":{
                  "themeMode":"system","playbackSpeed":"standard",
                  "commentSize":"standard","stageBackground":"black"
                }
              }
            }
        """.trimIndent()

        val document = (codec.decode(gzip(legacyJson)) as BackupDecodeResult.Success).document

        assertEquals(2, document.formatVersion)
        assertEquals(emptyList<TagBackupDto>(), document.payload.tags)
        assertEquals(emptyList<MemoTagRelationBackupDto>(), document.payload.memoTagRelations)
        assertEquals(true, document.payload.memos.single().isFavorite)
        assertEquals(true, document.payload.memos.single().isPinned)
    }

    @Test
    fun decodesVersionFiveJsonWithoutMotionFieldsAsStandardAuto() {
        val legacyJson = """
            {
              "format":"memorripple_backup",
              "formatVersion":5,
              "exportedAt":100,
              "appVersionName":"legacy",
              "appVersionCode":1,
              "payload":{
                "memos":[{"id":1,"title":"旧メモ","body":"本文","createdAt":10,"updatedAt":20}],
                "memoComments":[{
                  "id":2,"memoId":1,"text":"反応","createdAt":30,"playbackOrder":0,
                  "colorRole":"pink","sizeRole":"large","emphasisRole":"strong"
                }],
                "diaryEntries":[],
                "futureDiaryComments":[],
                "settings":{
                  "themeMode":"system","playbackSpeed":"standard",
                  "commentSize":"standard","stageBackground":"black"
                }
              }
            }
        """.trimIndent()

        val document = (codec.decode(gzip(legacyJson)) as BackupDecodeResult.Success).document
        val comment = document.payload.memoComments.single()

        assertEquals("pink", comment.colorRole)
        assertEquals("standard", comment.speedRole)
        assertEquals("auto", comment.placementRole)
        assertEquals("flow", comment.motionMode)
        assertEquals("rtl", comment.flowDirection)
        assertEquals("straight", comment.flowEffect)
        assertEquals("standard", BackupMapper.toRoomSnapshot(document).memoComments.single().motionSpeed)
    }

    @Test
    fun decodesVersionSixMotionWithoutModeAsFlow() {
        val legacyJson = """
            {
              "format":"memorripple_backup",
              "formatVersion":6,
              "exportedAt":100,
              "appVersionName":"legacy",
              "appVersionCode":1,
              "payload":{
                "memos":[{"id":1,"title":"旧メモ","body":"本文","createdAt":10,"updatedAt":20}],
                "memoComments":[{
                  "id":2,"memoId":1,"text":"反応","createdAt":30,"playbackOrder":0,
                  "speedRole":"fast","placementRole":"bottom"
                }],
                "diaryEntries":[],
                "futureDiaryComments":[],
                "settings":{
                  "themeMode":"system","playbackSpeed":"standard",
                  "commentSize":"standard","stageBackground":"black"
                }
              }
            }
        """.trimIndent()

        val document = (codec.decode(gzip(legacyJson)) as BackupDecodeResult.Success).document
        val restored = BackupMapper.toRoomSnapshot(document).memoComments.single()

        assertEquals("fast", restored.motionSpeed)
        assertEquals("bottom", restored.motionPlacement)
        assertEquals("flow", restored.motionMode)
    }

    @Test
    fun decodesVersionSevenFutureCommentWithoutExpressionAsDefaults() {
        val legacyJson = """
            {
              "format":"memorripple_backup",
              "formatVersion":7,
              "exportedAt":100,
              "appVersionName":"legacy",
              "appVersionCode":1,
              "payload":{
                "memos":[],
                "memoComments":[],
                "diaryEntries":[{
                  "id":1,"diaryDateEpochDay":20000,"body":"日記","state":"locked",
                  "createdAt":10,"updatedAt":20,"finalizedAt":20,
                  "correctionStartedAt":null,"lockedAt":20
                }],
                "futureDiaryComments":[{
                  "id":2,"diaryEntryId":1,"text":"未来","sealedAt":30,
                  "revealAt":40,"deliveredAt":null,"revealedAt":null,
                  "firstPresentedAt":null
                }],
                "settings":{
                  "themeMode":"system","playbackSpeed":"standard",
                  "commentSize":"standard","stageBackground":"black"
                }
              }
            }
        """.trimIndent()

        val document = (codec.decode(gzip(legacyJson)) as BackupDecodeResult.Success).document
        val restored = BackupMapper.toRoomSnapshot(document).futureDiaryComments.single()

        assertEquals("default", restored.appearanceColor)
        assertEquals("standard", restored.appearanceSize)
        assertEquals("normal", restored.appearanceEmphasis)
        assertEquals("flow", restored.motionMode)
    }

    @Test
    fun decodesVersionFourteenJsonWithoutKindAsMemos() {
        val legacyJson = """
            {
              "format":"memorripple_backup",
              "formatVersion":14,
              "exportedAt":100,
              "appVersionName":"legacy",
              "appVersionCode":3,
              "payload":{
                "memos":[
                  {"id":1,"title":"旧メモ","body":"本文","createdAt":10,"updatedAt":20},
                  {"id":2,"title":"骨組み","body":"- 項目\n  - 子","createdAt":10,"updatedAt":20}
                ],
                "memoComments":[],
                "diaryEntries":[],
                "futureDiaryComments":[],
                "settings":{
                  "themeMode":"system","playbackSpeed":"standard",
                  "commentSize":"standard","stageBackground":"black"
                }
              }
            }
        """.trimIndent()

        val document = (codec.decode(gzip(legacyJson)) as BackupDecodeResult.Success).document
        val restored = BackupMapper.toRoomSnapshot(document).memos

        assertEquals(14, document.formatVersion)
        assertEquals(listOf("memo", "memo"), document.payload.memos.map { it.kind })
        assertEquals(listOf("memo", "memo"), restored.map { it.kind })
        assertEquals("- 項目\n  - 子", restored[1].body)
    }

    @Test
    fun decodesVersionFifteenJsonWithAnOutline() {
        val json = """
            {
              "format":"memorripple_backup",
              "formatVersion":15,
              "exportedAt":100,
              "appVersionName":"current",
              "appVersionCode":4,
              "payload":{
                "memos":[
                  {"id":1,"title":"メモ","body":"本文","createdAt":10,"updatedAt":20,"kind":"memo"},
                  {"id":2,"title":"計画","body":"- 一","createdAt":10,"updatedAt":20,"kind":"outline"}
                ],
                "memoComments":[],
                "diaryEntries":[],
                "futureDiaryComments":[],
                "settings":{
                  "themeMode":"system","playbackSpeed":"standard",
                  "commentSize":"standard","stageBackground":"black"
                }
              }
            }
        """.trimIndent()

        val document = (codec.decode(gzip(json)) as BackupDecodeResult.Success).document

        assertEquals(listOf("memo", "outline"), BackupMapper.toRoomSnapshot(document).memos.map { it.kind })
        // The bytes a fresh encode writes decode to the same kinds.
        val again = (codec.decode(codec.encode(document)) as BackupDecodeResult.Success).document
        assertEquals(document, again)
    }

    private fun gzip(value: String): ByteArray = ByteArrayOutputStream().use { output ->
        GZIPOutputStream(output).use { it.write(value.toByteArray()) }
        output.toByteArray()
    }
}

class BackupFolderCodecTest {
    private val codec = BackupCodec()

    @Test
    fun decodesVersionFifteenJsonWithoutFoldersAsRootDocuments() {
        val json = """
            {"format":"memorripple_backup","formatVersion":15,"exportedAt":1,"appVersionName":"x","appVersionCode":3,
             "payload":{"memos":[{"id":1,"title":"a","body":"b","createdAt":1,"updatedAt":2,"kind":"outline"}],
             "memoComments":[],"diaryEntries":[],"futureDiaryComments":[],
             "settings":{"themeMode":"system","playbackSpeed":"standard","commentSize":"standard","stageBackground":"black"}}}
        """.trimIndent()
        val document = (codec.decode(gzip(json)) as BackupDecodeResult.Success).document
        val restored = BackupMapper.toRoomSnapshot(document)
        assertTrue(document.payload.folders.isEmpty())
        assertTrue(restored.folders.isEmpty())
        assertEquals(null, restored.memos.single().folderId)
        assertEquals("outline", restored.memos.single().kind)
    }

    @Test
    fun decodesVersionSixteenJsonWithNestedFoldersAndRoundTripsIt() {
        val json = """
            {"format":"memorripple_backup","formatVersion":16,"exportedAt":1,"appVersionName":"x","appVersionCode":4,
             "payload":{"memos":[
               {"id":1,"title":"仕様","body":"b","createdAt":1,"updatedAt":2,"kind":"memo","folderId":2},
               {"id":2,"title":"計画","body":"- 一","createdAt":1,"updatedAt":2,"kind":"outline","folderId":2},
               {"id":3,"title":"root","body":"b","createdAt":1,"updatedAt":2,"kind":"memo","folderId":null}],
             "memoComments":[],"diaryEntries":[],"futureDiaryComments":[],
             "folders":[{"id":1,"name":"開発","parentFolderId":null,"createdAt":1,"updatedAt":1},
                        {"id":2,"name":"Android","parentFolderId":1,"createdAt":1,"updatedAt":1}],
             "settings":{"themeMode":"system","playbackSpeed":"standard","commentSize":"standard","stageBackground":"black"}}}
        """.trimIndent()
        val document = (codec.decode(gzip(json)) as BackupDecodeResult.Success).document
        val restored = BackupMapper.toRoomSnapshot(document)
        assertEquals(listOf(null, 1L), restored.folders.map { it.parentFolderId })
        assertEquals(listOf(2L, 2L, null), restored.memos.map { it.folderId })
        val again = (codec.decode(codec.encode(document)) as BackupDecodeResult.Success).document
        assertEquals(document, again)
    }

    private fun gzip(value: String): ByteArray = java.io.ByteArrayOutputStream().use { output ->
        java.util.zip.GZIPOutputStream(output).use { it.write(value.toByteArray()) }
        output.toByteArray()
    }
}
