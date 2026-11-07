package com.swastik.mbfitness.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * The hard rules in docs/RULES.md that can be checked by reading the source. A rule only holds
 * for years if the build enforces it, so a broken one stops the build rather than waiting
 * for someone to notice in review.
 */
abstract class VerifySourceRulesTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val versionCatalog: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    private class Rule(
        val description: String,
        val pattern: Regex,
        val appliesTo: (String) -> Boolean = { true },
    )

    private fun rules() = listOf(
        Rule(
            "domain/ must stay pure Kotlin with no Android imports (hard rule 4)",
            Regex("""^\s*import\s+(android|androidx|com\.google\.android)\."""),
            appliesTo = { it.startsWith("domain/") },
        ),
        Rule(
            "Only sms/SimSmsSender.kt may touch SmsManager (hard rule 3)",
            Regex("""\bSmsManager\b"""),
            appliesTo = { it != "sms/SimSmsSender.kt" },
        ),
        Rule(
            "Only work/OutboxWorker.kt may send, through SmsSender (hard rule 3)",
            Regex("""\b\w*[sS]ender\.send\("""),
            appliesTo = { it != "work/OutboxWorker.kt" && !it.startsWith("sms/") },
        ),
        Rule(
            "Read the date through TodayProvider, never directly (docs/RULES.md conventions)",
            Regex("""\b(LocalDate|LocalDateTime|ZonedDateTime)\.now\(|ZoneId\.systemDefault\("""),
            appliesTo = { it != "domain/Dates.kt" },
        ),
        Rule(
            "fallbackToDestructiveMigration is banned (hard rule 7)",
            Regex("""fallbackToDestructiveMigration"""),
        ),
        Rule(
            "An Aadhaar value must never be logged (hard rule 11)",
            Regex("""(?i)(\bLog\.[vdiwe]\(|\bprintln\(|\bprint\(|errorLog|logError).*aadhaar"""),
        ),
        Rule(
            "No emoji in the UI (docs/RULES.md conventions)",
            Regex("""[\x{1F300}-\x{1FAFF}\x{2600}-\x{27BF}]"""),
            appliesTo = { it.startsWith("ui/") },
        ),
    )

    @TaskAction
    fun verify() {
        val problems = mutableListOf<String>()
        val root = sourceDir.get().asFile
        val packageRoot = root.resolve("com/swastik/mbfitness")
        val rules = rules()

        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val path = file.relativeTo(packageRoot).invariantSeparatorsPath
            val applicable = rules.filter { it.appliesTo(path) }
            file.readLines().forEachIndexed { index, line ->
                val code = line.trimStart()
                val isComment = code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")
                if (!isComment) {
                    applicable.filter { it.pattern.containsMatchIn(line) }.forEach { rule ->
                        problems += "$path:${index + 1}: ${rule.description}"
                    }
                }
            }
        }

        val versionLine = Regex("""^\s*[\w-]+\s*=\s*"([^"]+)"\s*$""")
        versionCatalog.get().asFile.readLines().forEachIndexed { index, line ->
            val version = versionLine.find(line)?.groupValues?.get(1) ?: return@forEachIndexed
            if ('+' in version || version.startsWith("latest") || version.endsWith("SNAPSHOT")) {
                problems += "libs.versions.toml:${index + 1}: dynamic version \"$version\" (hard rule 8)"
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException("Source rules broken:\n" + problems.joinToString("\n"))
        }
        report.get().asFile.writeText("ok\n")
    }
}
