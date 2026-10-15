// The root project builds nothing itself: it makes `./gradlew build` and `./gradlew test` build and test both parts,
// the core (an included build, whose tasks do not run on their own) and the CLI.
plugins {
    base
}

// The base plugin has no `test` task, so `./gradlew test` would only find the one of the CLI: this one brings the core's.
tasks.register("test") {
    group = "verification"
    description = "Runs the tests of the core and of the CLI."
    dependsOn(gradle.includedBuild("core").task(":test"))
}

tasks.named("build") {
    dependsOn(gradle.includedBuild("core").task(":build"))
    dependsOn(":cli:build")
}

tasks.named("check") {
    dependsOn(gradle.includedBuild("core").task(":check"))
}
