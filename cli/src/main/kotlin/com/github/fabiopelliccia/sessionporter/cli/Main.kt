package com.github.fabiopelliccia.sessionporter.cli

import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val environment = CliEnvironment.system()
    val cli = SessionPorterCli(environment)

    // Ctrl+C and SIGTERM end the JVM through its shutdown hooks. This one takes the temporary files away and reports
    // the interruption; the exit code is forced, because the JVM would otherwise end with 130 or 143 depending on the signal.
    // (Windows ends a process killed by a program, TerminateProcess, without running any hook: nothing can be done then.)
    Runtime.getRuntime().addShutdownHook(
        Thread {
            if (cli.interrupt()) Runtime.getRuntime().halt(ExitCode.INTERRUPTED)
        },
    )

    val exitCode = cli.run(ArgumentSource.resolve(args.toList(), environment.getenv))
    environment.out.flush()
    environment.err.flush()
    exitProcess(exitCode)
}
