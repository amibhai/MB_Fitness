package com.swastik.mbfitness.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Fails the build if the merged manifest holds a banned permission, or any permission
 * nobody has reviewed. Libraries can add permissions silently, so the merged file is
 * the one that counts (hard rules 1 and 2, SPEC 5.2 and 11.2).
 */
abstract class VerifyMergedManifestTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:Input
    abstract val allowedPermissions: SetProperty<String>

    @get:Input
    abstract val bannedPermissions: SetProperty<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(mergedManifest.get().asFile)

        val declared = sortedSetOf<String>()
        val required = sortedSetOf<String>()
        val everyElement = document.getElementsByTagName("*")
        for (index in 0 until everyElement.length) {
            val element = everyElement.item(index) as Element
            when (element.tagName) {
                "uses-permission", "uses-permission-sdk-23" ->
                    declared += element.getAttribute("android:name")
            }
            val guardedBy = element.getAttribute("android:permission")
            if (guardedBy.isNotEmpty()) required += guardedBy
        }

        val banned = bannedPermissions.get()
        val allowed = allowedPermissions.get()
        val foundBanned = (declared + required).filter { it in banned }
        val unreviewed = declared.filter { it !in banned && it !in allowed }

        if (foundBanned.isNotEmpty() || unreviewed.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("The merged manifest breaks the permission rules (SPEC 11.2).")
                    if (foundBanned.isNotEmpty()) appendLine("Banned: $foundBanned")
                    if (unreviewed.isNotEmpty()) appendLine("Not on the allow-list: $unreviewed")
                    append("Manifest: ${mergedManifest.get().asFile}")
                },
            )
        }
        report.get().asFile.writeText(declared.joinToString("\n", postfix = "\n"))
    }
}
