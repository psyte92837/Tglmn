package dev.tgscope.core.tdlib

import dev.g000sha256.tdl.dto.Message
import dev.g000sha256.tdl.dto.MessageAnimation
import dev.g000sha256.tdl.dto.MessageAudio
import dev.g000sha256.tdl.dto.MessageContent
import dev.g000sha256.tdl.dto.MessageDocument
import dev.g000sha256.tdl.dto.MessagePhoto
import dev.g000sha256.tdl.dto.MessageReactions
import dev.g000sha256.tdl.dto.MessageReplyToMessage
import dev.g000sha256.tdl.dto.MessageSenderChat
import dev.g000sha256.tdl.dto.MessageSenderUser
import dev.g000sha256.tdl.dto.MessageSticker
import dev.g000sha256.tdl.dto.MessageText
import dev.g000sha256.tdl.dto.MessageVideo
import dev.g000sha256.tdl.dto.MessageVideoNote
import dev.g000sha256.tdl.dto.MessageVoiceNote
import dev.g000sha256.tdl.dto.ReactionType
import dev.g000sha256.tdl.dto.ReactionTypeCustomEmoji
import dev.g000sha256.tdl.dto.ReactionTypeEmoji
import dev.g000sha256.tdl.dto.ReactionTypePaid
import dev.g000sha256.tdl.dto.User
import dev.g000sha256.tdl.dto.UserTypeBot

internal data class ContentInfo(val text: String, val mediaType: String?, val fileId: Int?)

internal fun MessageContent.info(): ContentInfo = when (val c = this) {
    is MessageText -> ContentInfo(c.text.text, null, null)
    is MessagePhoto -> ContentInfo(
        text = c.caption.text,
        mediaType = "photo",
        fileId = c.photo.sizes.maxByOrNull { it.width * it.height }?.photo?.id,
    )
    is MessageVideo -> ContentInfo(c.caption.text, "video", c.video.video.id)
    is MessageAnimation -> ContentInfo(c.caption.text, "animation", c.animation.animation.id)
    is MessageDocument -> ContentInfo(c.caption.text, "document", c.document.document.id)
    is MessageAudio -> ContentInfo(c.caption.text, "audio", c.audio.audio.id)
    is MessageVoiceNote -> ContentInfo(c.caption.text, "voice", c.voiceNote.voice.id)
    is MessageVideoNote -> ContentInfo("", "video_note", c.videoNote.video.id)
    is MessageSticker -> ContentInfo(c.sticker.emoji, "sticker", c.sticker.sticker.id)
    else -> ContentInfo(
        text = "",
        mediaType = c::class.simpleName?.removePrefix("Message")?.lowercase() ?: "other",
        fileId = null,
    )
}

internal fun ReactionType.label(): String = when (this) {
    is ReactionTypeEmoji -> emoji
    is ReactionTypeCustomEmoji -> "custom:$customEmojiId"
    is ReactionTypePaid -> "paid"
    else -> "other"
}

internal fun MessageReactions?.toTg(): List<TgReaction> =
    this?.reactions?.map { TgReaction(emoji = it.type.label(), count = it.totalCount) } ?: emptyList()

internal fun Message.toTg(): TgMessage {
    val content = content.info()
    val info = interactionInfo
    val sender = senderId
    val reply = replyTo as? MessageReplyToMessage
    return TgMessage(
        id = id,
        chatId = chatId,
        date = date.toLong(),
        editDate = editDate.toLong(),
        text = content.text,
        mediaType = content.mediaType,
        mediaFileId = content.fileId,
        views = info?.viewCount ?: 0,
        forwards = info?.forwardCount ?: 0,
        replyCount = info?.replyInfo?.replyCount ?: 0,
        reactions = info?.reactions.toTg(),
        senderUserId = (sender as? MessageSenderUser)?.userId,
        senderChatId = (sender as? MessageSenderChat)?.chatId,
        replyToMessageId = reply?.takeIf { it.chatId == 0L || it.chatId == chatId }?.messageId,
        isChannelPost = isChannelPost,
    )
}

internal fun User.toTg(): TgUser = TgUser(
    id = id,
    username = usernames?.activeUsernames?.firstOrNull(),
    firstName = firstName,
    lastName = lastName,
    isBot = type is UserTypeBot,
    isPremium = isPremium,
)
