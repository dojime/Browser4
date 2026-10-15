package ai.platon.pulsar.agentic.tools

/**
 * Stable error codes for the tool interface.
 *
 * Before this existed every failure was free text (`ERROR: crawl_status failed:
 * Custom domain 'crawl' is registered but no target object is available.`), so a
 * client could only pattern-match prose — and could not tell a retryable failure
 * from a permanent one. The code now travels with the result: in the text
 * (`ERROR: [TARGET_UNAVAILABLE] …`), in the MCP result `_meta.errorCode`, and in
 * the private dispatcher's JSON response, so both channels report the same thing.
 *
 * @property retryable whether repeating the same call may succeed
 * @property httpStatus the status a REST endpoint should return for this code
 * @property hint what the client should do about it (also surfaced in `_meta`)
 */
enum class ToolErrorCode(
    val retryable: Boolean,
    val httpStatus: Int,
    val hint: String,
) {
    /** The argument value does not fit the declared type or constraints. */
    INVALID_ARGUMENT(false, 400, "Check the value against the tool's input schema."),

    /** A required argument was omitted. */
    MISSING_REQUIRED_ARG(
        false, 400,
        "Provide the required argument (call `help {domain, method}` for the exact signature).",
    ),

    /** The client sent an argument the tool does not declare. */
    UNKNOWN_ARGUMENT(false, 400, "Remove the argument or check the tool's input schema."),

    /** No advertised tool matches the name. */
    UNKNOWN_TOOL(false, 404, "List the tools (`tools/list`) and call one of those names."),

    /** The requested session handle does not exist. */
    SESSION_NOT_FOUND(false, 404, "Open a session first or pass an existing sessionId."),

    /** The session exists but its browser is gone or unresponsive. */
    SESSION_UNHEALTHY(true, 409, "Re-open the session; the browser may have exited."),

    /** The domain needs a receiver this deployment cannot supply. */
    TARGET_UNAVAILABLE(false, 503, "The domain is registered but has no bound receiver in this deployment."),

    /**
     * The target the caller named — a snapshot element ref, a CSS selector, a
     * frame — does not resolve in the live page.
     *
     * Retrying the identical call can never help: refs are ephemeral and a
     * selector is what it is, so a client must re-read the page instead. It is
     * emphatically *not* [TIMEOUT]: the failure is immediate and permanent, and
     * reporting it as retryable sent agents into pointless retry loops. It is
     * not a usage error either — the call's shape was fine, the page simply did
     * not contain the target.
     */
    TARGET_NOT_FOUND(false, 404, "Re-run `snapshot` for fresh refs, or check the selector and the active frame (`frames`)."),

    /** Too many calls for this tool/session. */
    RATE_LIMITED(true, 429, "Wait for the reported retry-after and retry."),

    /** The tool did not finish in time. */
    TIMEOUT(true, 504, "Retry with a longer timeout, or poll the task status."),

    /**
     * A native JavaScript dialog (alert/confirm/prompt) is blocking the page.
     *
     * The action is parked, not lost: accepting or dismissing the dialog lets it
     * finish, so re-running it would duplicate the effect. Non-retryable on
     * purpose — the caller must handle the dialog first.
     */
    DIALOG_PENDING(false, 409, "Run dialog-accept or dialog-dismiss to release the page, then re-run the action only if it did not complete."),

    /** The browser rejected the command. */
    CDP_ERROR(true, 502, "The browser refused the command; retrying may help."),

    /** A downstream service failed. */
    UPSTREAM_ERROR(true, 502, "An upstream service failed; retrying may help."),

    /** The resource changed under the call. */
    CONFLICT(true, 409, "Re-read the resource and retry."),

    /** The task was cancelled. */
    CANCELLED(false, 499, "The task was cancelled; submit a new one if still needed."),

    /** Anything unexpected; the server log has the detail. */
    INTERNAL(false, 500, "Unexpected failure; check the server log for the request id."),
    ;

    /** The string a client sees on the wire. */
    val wire: String get() = name

    val isRetryable: Boolean get() = retryable
}

/**
 * A failure that already knows which [ToolErrorCode] it is.
 *
 * [ToolErrorMapper.classify] checks this before it starts inferring: a layer that holds
 * the fact should not have to phrase its message so that the inference guesses right.
 * The wording of an error must not decide its status code — this was written for the
 * `formats` layer, where "this build cannot deliver the format" and "the tool ran and
 * failed" are the same shape of exception with two very different answers.
 */
interface CodedFailure {
    val code: ToolErrorCode
}

/**
 * A target the caller named does not exist in the live page.
 *
 * Thrown when a syntactically valid target — an `eN` snapshot ref, a CSS
 * selector, a frame — cannot be resolved. Two things follow from that, and both
 * are why this is a type rather than a string:
 *
 * - [code] is [ToolErrorCode.TARGET_NOT_FOUND], so the client is told to re-read
 *   the page instead of retrying a call that can never succeed (the previous
 *   `IllegalArgumentException` reached the client as `TIMEOUT`, which is
 *   declared retryable);
 * - it deliberately does **not** extend `IllegalArgumentException`, which
 *   `MCPToolController.isUsageError` reads as "the caller misused the tool" and
 *   answers with the tool's signature/help dump. A lookup miss on a valid call
 *   must not be explained as a syntax mistake.
 *
 * @property code the wire code; [ToolErrorCode.TARGET_NOT_FOUND] unless a caller
 *   has a better one (an unsupported locator kind, say).
 */
class TargetNotFoundException(
    message: String,
    override val code: ToolErrorCode = ToolErrorCode.TARGET_NOT_FOUND,
) : RuntimeException(message), CodedFailure

/**
 * Maps a failure to a [ToolErrorCode].
 *
 * Both MCP channels share this mapping — an error reported as
 * `SESSION_NOT_FOUND` through `POST /mcp` must not read as `INTERNAL` through
 * `/mcp/call-tool`.
 */
object ToolErrorMapper {

    /** Classify [error] (and its cause chain) by type first, then by message. */
    fun classify(error: Throwable?): ToolErrorCode {
        if (error == null) return ToolErrorCode.INTERNAL
        val chain = generateSequence(error) { it.cause }.take(5).toList()
        val messages = chain.mapNotNull { it.message }.joinToString(" | ")

        // A layer that classified the failure itself wins over every inference below.
        chain.forEach { cause -> if (cause is CodedFailure) return cause.code }

        chain.forEach { cause ->
            when (cause::class.simpleName) {
                "TimeoutCancellationException", "TimeoutException", "SocketTimeoutException" ->
                    return ToolErrorCode.TIMEOUT
                "InterruptedException" -> return ToolErrorCode.CANCELLED
            }
        }

        val byMessage = classifyMessage(messages)
        if (byMessage != ToolErrorCode.INTERNAL) return byMessage

        return when (error) {
            is IllegalArgumentException -> ToolErrorCode.INVALID_ARGUMENT
            is UnsupportedOperationException -> ToolErrorCode.TARGET_UNAVAILABLE
            is IllegalStateException -> ToolErrorCode.INTERNAL
            else -> ToolErrorCode.INTERNAL
        }
    }

    /**
     * Classify a failure message.
     *
     * The patterns match the wording the executors already produce, so existing
     * call sites get a code without being rewritten.
     */
    fun classifyMessage(message: String?): ToolErrorCode {
        val text = message?.lowercase().orEmpty()
        if (text.isBlank()) return ToolErrorCode.INTERNAL

        return when {
            "session not found" in text -> ToolErrorCode.SESSION_NOT_FOUND
            "no active session" in text -> ToolErrorCode.SESSION_NOT_FOUND
            "unhealthy" in text || "not healthy" in text -> ToolErrorCode.SESSION_UNHEALTHY

            "no target object is available" in text -> ToolErrorCode.TARGET_UNAVAILABLE
            "requires a registered commandrunner" in text -> ToolErrorCode.TARGET_UNAVAILABLE
            "unsupported receiver" in text -> ToolErrorCode.TARGET_UNAVAILABLE
            "custom domain" in text && "no target" in text -> ToolErrorCode.TARGET_UNAVAILABLE

            // Checked before the argument patterns below: a lookup miss on a
            // valid call must not be read as a malformed one.
            isTargetMiss(text) -> ToolErrorCode.TARGET_NOT_FOUND

            // A pending native dialog parks the action rather than failing it —
            // report it as its own state, never as INTERNAL and never as
            // something to blindly retry.
            "opened a native" in text || "blocked by a native" in text -> ToolErrorCode.DIALOG_PENDING

            "missing required parameter" in text -> ToolErrorCode.MISSING_REQUIRED_ARG
            "missing parameter" in text -> ToolErrorCode.MISSING_REQUIRED_ARG
            "is required" in text -> ToolErrorCode.MISSING_REQUIRED_ARG
            // Executors phrase the alternative-arguments case as
            // "eval requires 'expression' or ('expression','selector')"; matching
            // the quoted argument name keeps every such message out of INTERNAL.
            "requires '" in text || "requires \"" in text -> ToolErrorCode.MISSING_REQUIRED_ARG

            "unknown tool" in text -> ToolErrorCode.UNKNOWN_TOOL
            "unsupported domain" in text -> ToolErrorCode.UNKNOWN_TOOL
            "unsupported method" in text -> ToolErrorCode.UNKNOWN_TOOL
            "extraneous parameter" in text -> ToolErrorCode.UNKNOWN_ARGUMENT

            // The X-SQL pre-validation rejects a statement it will not run
            // (non-SELECT, multi-statement, forbidden verb) — a client-input
            // rejection, so it must not be reported as a server fault.
            "only select statements are supported" in text -> ToolErrorCode.INVALID_ARGUMENT
            "only one statement is supported" in text -> ToolErrorCode.INVALID_ARGUMENT
            "statement is forbidden" in text -> ToolErrorCode.INVALID_ARGUMENT
            "argument is not allowed" in text -> ToolErrorCode.INVALID_ARGUMENT

            "rate limit" in text || "too many requests" in text -> ToolErrorCode.RATE_LIMITED
            isTimeoutPhrase(text) -> ToolErrorCode.TIMEOUT
            "cancelled" in text || "canceled" in text -> ToolErrorCode.CANCELLED

            "cdp" in text || "devtools" in text || "browserprotocol" in text -> ToolErrorCode.CDP_ERROR
            // A DOM node id can go stale between resolution and use (a concurrent
            // click, a re-render, a frame swap). The node is gone, not the request:
            // tell the client to retry rather than reporting INTERNAL.
            "no node with given id" in text -> ToolErrorCode.CDP_ERROR
            "node is detached" in text || "element is not attached" in text -> ToolErrorCode.CDP_ERROR
            "connection refused" in text || "connection reset" in text -> ToolErrorCode.UPSTREAM_ERROR
            "conflict" in text || "already exists" in text -> ToolErrorCode.CONFLICT

            else -> ToolErrorCode.INTERNAL
        }
    }

    /**
     * True when [message] reports a target the live page does not contain.
     *
     * Shared with the help-text decision in the REST dispatcher, so the two can
     * never disagree about whether a lookup miss is a usage error.
     */
    fun isTargetMiss(message: String): Boolean {
        val text = message.lowercase()
        return "element not found for ref" in text ||
            "no element matches selector" in text ||
            "option target not found" in text ||
            "option target could not be resolved" in text ||
            "frame not found" in text ||
            ("selected frame" in text && "not reachable" in text)
    }

    /**
     * True when [text] (already lowercased) reports a timeout.
     *
     * Token-aware on purpose: a bare `"timeout" in text` matched the *parameter
     * name* `waitTimeout` inside the tool description the dispatcher appended to
     * a stale-ref failure, so a permanent not-found error came back as a
     * retryable [ToolErrorCode.TIMEOUT]. Parameter names must not decide a
     * status code; only the words that report one may.
     */
    private fun isTimeoutPhrase(text: String): Boolean =
        "timed out" in text || TIMEOUT_WORD.containsMatchIn(text)

    private val TIMEOUT_WORD = Regex("""\btimeouts?\b""")
}
