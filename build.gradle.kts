// The root project builds nothing itself: it makes `./gradlew build` build and test both parts, the
// core (an included build, whose tasks do not run on their own) and the CLI.
plugins {
    base
}

tasks.named("build") {
    dependsOn(gradle.includedBuild("core").task(":build"))
    dependsOn(":cli:build")
}

tasks.named("check") {
    dependsOn(gradle.includedBuild("core").task(":check"))
}
