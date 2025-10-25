package io.qent.bro.cli

import com.github.ajalt.clikt.core.CliktCommand
import io.qent.bro.core.coreGreeting

private class BroCli : CliktCommand(name = "bro") {
    override fun run() {
        echo("Hello from CLI using \"${coreGreeting()}\"")
    }
}

fun main(args: Array<String>) {
    BroCli().main(args)
}
