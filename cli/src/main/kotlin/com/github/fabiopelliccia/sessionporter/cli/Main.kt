package com.github.fabiopelliccia.sessionporter.cli

import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val environment = CliEnvironment.system()
    val exitCode = SessionPorterCli(environment).run(args.toList())
    environment.out.flush()
    environment.err.flush()
    exitProcess(exitCode)
}
