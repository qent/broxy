package io.qent.broxy.cli.support

import io.qent.broxy.cli.commands.LogLevelOption
import io.qent.broxy.core.utils.CompositeLogger
import io.qent.broxy.core.utils.DailyFileLogger
import io.qent.broxy.core.utils.FilteredLogger
import io.qent.broxy.core.utils.Logger
import io.qent.broxy.core.utils.StdErrLogger
import java.nio.file.Path

internal object CliLoggerFactory {
    fun create(
        level: LogLevelOption,
        baseDir: Path,
    ): Logger =
        CompositeLogger(
            FilteredLogger(level.toLogLevel(), StdErrLogger),
            DailyFileLogger(baseDir),
        )
}
