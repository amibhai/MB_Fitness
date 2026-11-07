package com.swastik.mbfitness.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Lists everything reachable from [root] as "group:module:version", including transitive
 * dependencies.
 */
fun collectModules(root: ResolvedComponentResult): List<String> {
    val seen = LinkedHashSet<ResolvedComponentResult>()
    val pending = ArrayDeque(listOf(root))
    while (pending.isNotEmpty()) {
        val component = pending.removeFirst()
        if (seen.add(component)) {
            component.dependencies
                .filterIsInstance<ResolvedDependencyResult>()
                .forEach { pending.addLast(it.selected) }
        }
    }
    return seen
        .mapNotNull { it.id as? ModuleComponentIdentifier }
        .map { "${it.group}:${it.module}:${it.version}" }
        .sorted()
}

/**
 * Fails on anything banned by SPEC 5.2. A banned SDK can arrive through another library, so
 * checking the version catalog alone would not be enough.
 */
abstract class VerifyDependenciesTask : DefaultTask() {
    @get:Input
    abstract val modules: ListProperty<String>

    @get:Input
    abstract val bannedGroupPrefixes: SetProperty<String>

    @get:Input
    abstract val bannedModules: SetProperty<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val prefixes = bannedGroupPrefixes.get()
        val exact = bannedModules.get()
        val all = modules.get()
        val banned = all.filter { coordinates ->
            val (group, module) = coordinates.split(":")
            "$group:$module" in exact ||
                prefixes.any { prefix -> group == prefix || group.startsWith("$prefix.") }
        }
        if (banned.isNotEmpty()) {
            throw GradleException("Banned dependencies (SPEC 5.2): ${banned.joinToString()}")
        }
        report.get().asFile.writeText(all.joinToString("\n", postfix = "\n"))
    }
}
