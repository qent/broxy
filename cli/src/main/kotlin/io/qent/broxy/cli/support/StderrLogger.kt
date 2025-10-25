package io.qent.broxy.cli.support

import io.qent.broxy.core.utils.Logger
import io.qent.broxy.core.utils.StdErrLogger

/**
 * Simple logger that writes to STDERR to avoid corrupting STDIO MCP streams.
 */
object StderrLogger : Logger by StdErrLogger
