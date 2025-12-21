package io.qent.broxy.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import io.qent.broxy.cli.commands.ProxyCommand

private class BroxyCli : CliktCommand(name = "broxy") {
    override fun run() {
        echo("Broxy CLI")
    }
}

fun main(args: Array<String>) {
    BroxyCli()
        .subcommands(ProxyCommand())
        .main(args)
}
