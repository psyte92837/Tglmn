package dev.tgscope.core.tdlib

import android.os.Build
import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.AuthorizationState
import dev.g000sha256.tdl.dto.AuthorizationStateClosed
import dev.g000sha256.tdl.dto.AuthorizationStateClosing
import dev.g000sha256.tdl.dto.AuthorizationStateLoggingOut
import dev.g000sha256.tdl.dto.AuthorizationStateReady
import dev.g000sha256.tdl.dto.AuthorizationStateWaitCode
import dev.g000sha256.tdl.dto.AuthorizationStateWaitPassword
import dev.g000sha256.tdl.dto.AuthorizationStateWaitPhoneNumber
import dev.g000sha256.tdl.dto.AuthorizationStateWaitTdlibParameters
import dev.g000sha256.tdl.dto.ChatTypeSupergroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Single entry point to Telegram. Wraps a [TdlClient], drives the authorization state machine
 * and maps TDLib objects to the app's own models so that the rest of the code never touches DTOs.
 */
class TelegramClient(
    private val databaseDir: java.io.File,
    private val filesDir: java.io.File,
    private val appVersion: String,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _authState = MutableStateFlow<AuthState>(AuthState.Initializing)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _updates = MutableSharedFlow<TgUpdate>(
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Live updates (new messages, counters, edits, deletions) for opened chats. */
    val updates: SharedFlow<TgUpdate> = _updates.asSharedFlow()

    @Volatile
    private var credentials: ApiCredentials? = null

    @Volatile
    private var client: TdlClient

    private var clientJob: Job

    init {
        val (c, job) = createAndBind()
        client = c
        clientJob = job
    }

    // region lifecycle & auth

    /** Starts TDLib with the given credentials, or asks the UI for them when null. */
    fun start(creds: ApiCredentials?) {
        credentials = creds
        if (creds == null) {
            _authState.value = AuthState.NeedCredentials
            return
        }
        _authState.value = AuthState.Initializing
        // TDLib emits its first authorization update only after the first request.
        scope.launch { client.getAuthorizationState() }
    }

    suspend fun awaitReady(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { authState.first { it is AuthState.Ready } } != null

    suspend fun sendPhone(phone: String) {
        call { setAuthenticationPhoneNumber(phoneNumber = phone.filter { it.isDigit() || it == '+' }, settings = null) }
    }

    suspend fun sendCode(code: String) {
        call { checkAuthenticationCode(code = code.trim()) }
    }

    suspend fun sendPassword(password: String) {
        call { checkAuthenticationPassword(password = password) }
    }

    suspend fun logOut() {
        call { logOut() }
    }

    /** Logs out and wipes the local TDLib database (used to change API credentials). */
    suspend fun destroy(newCredentials: ApiCredentials?) {
        credentials = newCredentials
        call { destroy() }
    }

    private fun createAndBind(): Pair<TdlClient, Job> {
        val c = TdlClient.create()
        val job = SupervisorJob(scope.coroutineContext[Job])
        val s = CoroutineScope(scope.coroutineContext + job)
        // Subscribe before any request is sent, as required by TDL Coroutines.
        s.launch(start = CoroutineStart.UNDISPATCHED) {
            c.authorizationStateUpdates.collect { onAuthState(c, it.authorizationState) }
        }
        s.launch(start = CoroutineStart.UNDISPATCHED) {
            c.newMessageUpdates.collect { _updates.emit(TgUpdate.NewMessage(it.message.toTg())) }
        }
        s.launch(start = CoroutineStart.UNDISPATCHED) {
            c.messageInteractionInfoUpdates.collect {
                val info = it.interactionInfo
                _updates.emit(
                    TgUpdate.InteractionInfo(
                        chatId = it.chatId,
                        messageId = it.messageId,
                        views = info?.viewCount ?: 0,
                        forwards = info?.forwardCount ?: 0,
                        replyCount = info?.replyInfo?.replyCount ?: 0,
                        reactions = info?.reactions.toTg(),
                    ),
                )
            }
        }
        s.launch(start = CoroutineStart.UNDISPATCHED) {
            c.messageContentUpdates.collect { _updates.emit(TgUpdate.ContentChanged(it.chatId, it.messageId)) }
        }
        s.launch(start = CoroutineStart.UNDISPATCHED) {
            c.deleteMessagesUpdates.collect {
                if (it.isPermanent && !it.fromCache) {
                    _updates.emit(TgUpdate.Deleted(it.chatId, it.messageIds.toList()))
                }
            }
        }
        return c to job
    }

    private suspend fun onAuthState(c: TdlClient, state: AuthorizationState) {
        when (state) {
            is AuthorizationStateWaitTdlibParameters -> {
                val cr = credentials
                if (cr == null) {
                    _authState.value = AuthState.NeedCredentials
                    return
                }
                val result = c.setTdlibParameters(
                    useTestDc = false,
                    databaseDirectory = databaseDir.absolutePath,
                    filesDirectory = filesDir.absolutePath,
                    databaseEncryptionKey = cr.databaseKey,
                    useFileDatabase = true,
                    useChatInfoDatabase = true,
                    useMessageDatabase = true,
                    useSecretChats = false,
                    apiId = cr.apiId,
                    apiHash = cr.apiHash,
                    systemLanguageCode = Locale.getDefault().language.ifEmpty { "en" },
                    deviceModel = Build.MODEL ?: "Android",
                    systemVersion = "Android ${Build.VERSION.RELEASE}",
                    applicationVersion = appVersion,
                )
                if (result is TdlResult.Failure) {
                    _authState.value = AuthState.Error(result.message)
                }
            }
            is AuthorizationStateWaitPhoneNumber -> _authState.value = AuthState.WaitPhone
            is AuthorizationStateWaitCode -> _authState.value = AuthState.WaitCode(state.codeInfo.phoneNumber)
            is AuthorizationStateWaitPassword -> _authState.value = AuthState.WaitPassword(state.passwordHint)
            is AuthorizationStateReady -> _authState.value = AuthState.Ready
            is AuthorizationStateLoggingOut, is AuthorizationStateClosing -> _authState.value = AuthState.LoggingOut
            is AuthorizationStateClosed -> {
                _authState.value = AuthState.Closed
                // A closed TDLib instance cannot be reused: spin up a fresh one.
                scope.launch {
                    clientJob.cancel()
                    val (fresh, job) = createAndBind()
                    client = fresh
                    clientJob = job
                    start(credentials)
                }
            }
            else -> _authState.value = AuthState.Unsupported(state::class.simpleName ?: "unknown")
        }
    }

    // endregion

    // region data access

    /** Resolves `@name`, `name` or `https://t.me/name` to a channel with its discussion group. */
    suspend fun resolveChannel(input: String): TgChannel {
        val username = normalizeUsername(input)
        require(username.isNotEmpty()) { "Empty channel name" }
        val chat = call { searchPublicChat(username = username) }
        val type = chat.type as? ChatTypeSupergroup
            ?: throw TgException(400, "@$username is not a channel")
        if (!type.isChannel) throw TgException(400, "@$username is a group, not a channel")
        val full = call { getSupergroupFullInfo(supergroupId = type.supergroupId) }
        if (full.linkedChatId != 0L) {
            // Make the discussion group known to TDLib so thread requests resolve.
            runCatching { call { getChat(chatId = full.linkedChatId) } }
        }
        return TgChannel(
            chatId = chat.id,
            username = username,
            title = chat.title,
            discussionChatId = full.linkedChatId,
        )
    }

    /** Messages older than [fromMessageId] (0 = newest), newest first. May return fewer than [limit]. */
    suspend fun getHistory(chatId: Long, fromMessageId: Long, limit: Int): List<TgMessage> =
        call {
            getChatHistory(
                chatId = chatId,
                fromMessageId = fromMessageId,
                offset = 0,
                limit = limit.coerceIn(1, 100),
                onlyLocal = false,
            )
        }.messages.filterNotNull().map { it.toTg() }

    /** Re-fetches known messages; null entries mean the message no longer exists. */
    suspend fun getMessages(chatId: Long, ids: List<Long>): List<TgMessage?> {
        if (ids.isEmpty()) return emptyList()
        return call { getMessages(chatId = chatId, messageIds = ids.toLongArray()) }
            .messages.map { it?.toTg() }
    }

    /** Comments of a channel post, newest first, older than [fromMessageId] (0 = newest). */
    suspend fun getComments(channelChatId: Long, postId: Long, fromMessageId: Long, limit: Int): List<TgMessage> =
        call {
            getMessageThreadHistory(
                chatId = channelChatId,
                messageId = postId,
                fromMessageId = fromMessageId,
                offset = 0,
                limit = limit.coerceIn(1, 100),
            )
        }.messages.filterNotNull().map { it.toTg() }

    suspend fun getUser(userId: Long): TgUser = call { getUser(userId = userId) }.toTg()

    /** Downloads a file synchronously and returns its local path. */
    suspend fun downloadFile(fileId: Int): String? =
        call { downloadFile(fileId = fileId, priority = 1, offset = 0, limit = 0, synchronous = true) }
            .local.path.takeIf { it.isNotEmpty() }

    /** Opening a chat makes TDLib deliver live updates for it, even when not joined. */
    suspend fun openChat(chatId: Long) {
        runCatching { call { openChat(chatId = chatId) } }
    }

    suspend fun closeChat(chatId: Long) {
        runCatching { call { closeChat(chatId = chatId) } }
    }

    // endregion

    private suspend fun <T> call(block: suspend TdlClient.() -> TdlResult<T>): T =
        when (val result = client.block()) {
            is TdlResult.Success -> result.result
            is TdlResult.Failure -> throw toException(result.code, result.message)
        }

    companion object {
        private val retryAfter = Regex("retry after (\\d+)", RegexOption.IGNORE_CASE)

        internal fun toException(code: Int, message: String): TgException {
            if (code == 429) {
                val seconds = retryAfter.find(message)?.groupValues?.get(1)?.toIntOrNull() ?: 30
                return FloodWaitException(seconds, message)
            }
            return TgException(code, message)
        }

        fun normalizeUsername(input: String): String = input.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("t.me/")
            .removePrefix("telegram.me/")
            .removePrefix("@")
            .substringBefore('/')
            .substringBefore('?')
            .trim()
    }
}
