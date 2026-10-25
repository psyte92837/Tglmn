package dev.tgscope.core.tdlib

/** Credentials of a Telegram API application (https://my.telegram.org/apps). */
data class ApiCredentials(
    val apiId: Int,
    val apiHash: String,
    /** Key that encrypts the local TDLib database. */
    val databaseKey: ByteArray,
)

sealed interface AuthState {
    data object Initializing : AuthState
    data object NeedCredentials : AuthState
    data object WaitPhone : AuthState
    data class WaitCode(val phoneNumber: String) : AuthState
    data class WaitPassword(val hint: String) : AuthState

    /** Steps this app does not implement (e-mail login, registration, QR on other device). */
    data class Unsupported(val step: String) : AuthState
    data object Ready : AuthState
    data object LoggingOut : AuthState
    data object Closed : AuthState
    data class Error(val message: String) : AuthState
}

data class TgChannel(
    val chatId: Long,
    val username: String,
    val title: String,
    /** Linked discussion group, 0 if comments are disabled. */
    val discussionChatId: Long,
)

data class TgReaction(val emoji: String, val count: Int)

data class TgMessage(
    val id: Long,
    val chatId: Long,
    val date: Long,
    val editDate: Long,
    val text: String,
    val mediaType: String?,
    val mediaFileId: Int?,
    val views: Int,
    val forwards: Int,
    val replyCount: Int,
    val reactions: List<TgReaction>,
    val senderUserId: Long?,
    val senderChatId: Long?,
    val replyToMessageId: Long?,
    val isChannelPost: Boolean,
) {
    /** Server-side message id as seen in t.me links. TDLib ids are server ids shifted by 20 bits. */
    val serverId: Long get() = id shr 20
}

data class TgUser(
    val id: Long,
    val username: String?,
    val firstName: String,
    val lastName: String,
    val isBot: Boolean,
    val isPremium: Boolean,
)

sealed interface TgUpdate {
    val chatId: Long

    data class NewMessage(val message: TgMessage) : TgUpdate {
        override val chatId: Long get() = message.chatId
    }

    data class InteractionInfo(
        override val chatId: Long,
        val messageId: Long,
        val views: Int,
        val forwards: Int,
        val replyCount: Int,
        val reactions: List<TgReaction>,
    ) : TgUpdate

    data class ContentChanged(override val chatId: Long, val messageId: Long) : TgUpdate

    data class Deleted(override val chatId: Long, val messageIds: List<Long>) : TgUpdate
}

open class TgException(val code: Int, message: String) : Exception("[$code] $message")

/** Telegram asked to slow down: nothing may be requested for [retryAfterSeconds]. */
class FloodWaitException(val retryAfterSeconds: Int, message: String) : TgException(429, message)

object TgLinks {
    fun post(username: String, postServerId: Long): String =
        "https://t.me/$username/$postServerId"

    fun comment(username: String, postServerId: Long, commentServerId: Long): String =
        "https://t.me/$username/$postServerId?comment=$commentServerId"
}
