package io.github.cragcoffee.memoripple.ui.outline

import io.github.cragcoffee.memoripple.data.MemoCommentEntity
import io.github.cragcoffee.memoripple.data.PhotoAttachment
import io.github.cragcoffee.memoripple.domain.memos.LinkableMemo
import io.github.cragcoffee.memoripple.domain.memos.MemoTemplate
import io.github.cragcoffee.memoripple.ui.attachments.AttachmentImageLoader

/**
 * What the memo editor's writing aids need when they stand on the outliner's bar: the
 * templates, the memos a `[[title]]` can point at, the comments a `[R n]` can mark, the photos
 * kept with this memo and the way to add or drop one — all read from the same view model the
 * memo editor drives. The outline document itself is never in here.
 */
class OutlinerAids(
    val templates: List<MemoTemplate>,
    val onDeleteTemplate: (String) -> Unit,
    val onCreateTemplate: (String, String, (Boolean) -> Unit) -> Unit,
    val linkTargets: List<LinkableMemo>,
    val comments: List<MemoCommentEntity>,
    val onLinkComment: (MemoCommentEntity, (Int?) -> Unit) -> Unit,
    val onOpenComments: () -> Unit,
    val onAddPhoto: () -> Unit,
    val photos: List<PhotoAttachment>,
    val importingPhotos: Boolean,
    val onDeletePhoto: (PhotoAttachment) -> Unit,
    val imageLoader: AttachmentImageLoader,
    val onMessage: (String) -> Unit,
)
