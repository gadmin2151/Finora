plugins {
    id("com.android.application") version "9.3.2" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10" apply false
}

val kotlinFormatter by configurations.creating {
    attributes {
        attribute(
            org.gradle.api.attributes.Bundling.BUNDLING_ATTRIBUTE,
            objects.named(org.gradle.api.attributes.Bundling.SHADOWED),
        )
    }
}

dependencies { kotlinFormatter("com.facebook:ktfmt:0.64") }

val kotlinSources =
    fileTree(rootDir) {
        include("**/*.kt", "**/*.kts")
        exclude("**/build/**", ".gradle/**", ".kotlin/**")
    }

listOf("formatKotlin" to false, "checkKotlinFormat" to true).forEach { (taskName, checkOnly) ->
    tasks.register<JavaExec>(taskName) {
        group = "verification"
        description = if (checkOnly) "Check Kotlin formatting" else "Format Kotlin sources"
        classpath = kotlinFormatter
        mainClass.set("com.facebook.ktfmt.cli.Main")
        doFirst {
            args =
                listOf("--kotlinlang-style") +
                    (if (checkOnly) listOf("--dry-run", "--set-exit-if-changed") else emptyList()) +
                    kotlinSources.files.sortedBy { it.path }.map { it.absolutePath }
        }
    }
}
