package com.swastik.mbfitness.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/** Fails the build if line coverage of one package drops below a minimum (SPEC 17, Phase 1). */
abstract class VerifyCoverageTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val coverageReport: RegularFileProperty

    @get:Input
    abstract val packagePath: Property<String>

    @get:Input
    abstract val minimumLinePercent: Property<Int>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val factory = DocumentBuilderFactory.newInstance()
        // JaCoCo's XML names a DTD. Don't try to fetch it.
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        val document = factory.newDocumentBuilder().parse(coverageReport.get().asFile)

        val packages = document.getElementsByTagName("package")
        val target = (0 until packages.length)
            .map { packages.item(it) as Element }
            .firstOrNull { it.getAttribute("name") == packagePath.get() }
            ?: throw GradleException("No coverage data for ${packagePath.get()}")

        val children = target.childNodes
        val lines = (0 until children.length)
            .map { children.item(it) }
            .filterIsInstance<Element>()
            .first { it.tagName == "counter" && it.getAttribute("type") == "LINE" }
        val covered = lines.getAttribute("covered").toLong()
        val missed = lines.getAttribute("missed").toLong()
        val percent = 100.0 * covered / (covered + missed)

        if (percent < minimumLinePercent.get()) {
            throw GradleException(
                "Line coverage of ${packagePath.get()} is %.1f%%, below the required %d%%"
                    .format(percent, minimumLinePercent.get()),
            )
        }
        report.get().asFile.writeText("%.1f%% of %d lines\n".format(percent, covered + missed))
    }
}
