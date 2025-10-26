package io.qent.bro.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import io.qent.bro.cli.commands.ProxyCommand

private class BroCli : CliktCommand(name = "bro") {
    override fun run() {
        echo("bro CLI")
    }
}

fun main(args: Array<String>) {
    BroCli()
        .subcommands(ProxyCommand())
        .main(args)
}
