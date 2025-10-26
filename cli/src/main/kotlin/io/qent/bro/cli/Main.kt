package io.qent.bro.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import io.qent.bro.cli.commands.ProxyCommand
import io.qent.bro.core.coreGreeting

private class BroCli : CliktCommand(name = "bro") {
    override fun run() {
        echo("bro CLI — core says: ${coreGreeting()}")
    }
}

fun main(args: Array<String>) {
    BroCli()
        .subcommands(ProxyCommand())
        .main(args)
}
