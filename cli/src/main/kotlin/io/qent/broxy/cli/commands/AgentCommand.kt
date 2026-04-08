package io.qent.broxy.cli.commands

import com.github.ajalt.clikt.core.CliktCommand

open class AgentCommand : CliktCommand(name = "agent", help = "Run Broxy agent commands") {
    override fun run() = Unit
}
