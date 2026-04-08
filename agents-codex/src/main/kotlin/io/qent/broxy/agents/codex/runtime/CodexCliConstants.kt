package io.qent.broxy.agents.codex.runtime

internal const val CODEX_WEB_SEARCH_LIVE = "live"
internal const val CODEX_WEB_SEARCH_DISABLED = "disabled"
internal const val CODEX_SANDBOX_READ_ONLY = "read-only"
internal const val CODEX_SANDBOX_WORKSPACE_WRITE = "workspace-write"
internal const val CODEX_APPROVAL_POLICY_NEVER = "never"
internal const val CODEX_MAX_AUTH_RETRY_ATTEMPTS = 1
internal const val CODEX_AUTH_FILE_CHANGE_WAIT_TIMEOUT_MILLIS = 1_500L
internal const val CODEX_AUTH_FILE_CHANGE_POLL_INTERVAL_MILLIS = 100L
internal const val CODEX_SHORT_COMMAND_TIMEOUT_SECONDS = 15L
internal const val CODEX_PROCESS_SHUTDOWN_TIMEOUT_SECONDS = 4L
internal const val CODEX_PROCESS_FORCE_SHUTDOWN_TIMEOUT_SECONDS = 2L
internal const val CODEX_IO_THREAD_JOIN_TIMEOUT_MILLIS = 2_000L
internal const val CODEX_REFRESH_TOKEN_REUSED_MARKER = "refresh token was already used"
internal const val CODEX_LOGIN_STATUS_MARKER = "logged in"
internal const val CODEX_DEFAULT_FAILURE_KIND = "execution"
