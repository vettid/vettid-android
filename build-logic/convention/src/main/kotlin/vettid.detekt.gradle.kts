// Static analysis. detekt 1.23.x is compiled against an older Kotlin, so its
// own classpath is pinned to that version (the project's Kotlin must not leak in).
plugins {
    id("io.gitlab.arturbosch.detekt")
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
    parallel = false
    source.setFrom("src/main/kotlin", "src/test/kotlin", "src/debug/kotlin", "src/release/kotlin", "src/debugTools/kotlin", "src/devStack/kotlin")
}

val detektKotlin = libs.findVersion("detektKotlin").get().requiredVersion
configurations.matching { it.name == "detekt" }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") useVersion(detektKotlin)
    }
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "17"
    reports {
        html.required.set(true)
        sarif.required.set(true)
        xml.required.set(false)
        txt.required.set(false)
        md.required.set(false)
    }
}
