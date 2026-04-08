package io.qent.broxy.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import io.qent.broxy.cli.commands.AgentCommand
import io.qent.broxy.cli.commands.AgentRunCommand
import io.qent.broxy.cli.commands.ProxyCommand

private class BroxyCli : CliktCommand(name = "broxy") {
    override fun run() = Unit
}

fun main(args: Array<String>) {
    BroxyCli()
        .subcommands(
            ProxyCommand(),
            AgentCommand().subcommands(AgentRunCommand()),
        ).main(args)
}
