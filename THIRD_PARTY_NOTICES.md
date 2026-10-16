# Third-party notices

Session Porter is licensed under the [MIT License](LICENSE). The jar the plugin runs (`plugin/lib/session-porter.jar`)
bundles the following software. All of it is licensed under the Apache License, Version 2.0, whose text is in
[cli/src/main/licenses/Apache-2.0.txt](cli/src/main/licenses/Apache-2.0.txt) and inside the jar in `META-INF/licenses/`.

| Software | Version | License | Source |
|---|---|---|---|
| Kotlin standard library (`org.jetbrains.kotlin:kotlin-stdlib`) | 2.3.21 | Apache-2.0 | https://github.com/JetBrains/kotlin |
| JetBrains annotations (`org.jetbrains:annotations`) | 13.0 | Apache-2.0 | https://github.com/JetBrains/java-annotations |
| Gson (`com.google.code.gson:gson`) | 2.11.0 | Apache-2.0 | https://github.com/google/gson |

- Kotlin standard library: Copyright JetBrains s.r.o. and Kotlin Programming Language contributors.
- JetBrains annotations: Copyright JetBrains s.r.o.
- Gson: Copyright Google Inc.

The same list, without versions, is in the jar as `META-INF/licenses/THIRD-PARTY.txt`.

Nothing else is shipped. The tools used to build the project (Gradle, the Kotlin compiler, the Shadow plugin, JUnit)
are not part of the jar or of the plugin.
