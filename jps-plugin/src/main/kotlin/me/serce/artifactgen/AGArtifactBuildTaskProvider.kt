package me.serce.artifactgen

import com.intellij.execution.process.BaseOSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.text.StringUtil
import org.jetbrains.jps.builders.artifacts.ArtifactBuildTaskProvider
import org.jetbrains.jps.incremental.BuildTask
import org.jetbrains.jps.incremental.CompileContext
import org.jetbrains.jps.incremental.ProjectBuildException
import org.jetbrains.jps.incremental.StopBuildException
import org.jetbrains.jps.incremental.messages.BuildMessage
import org.jetbrains.jps.incremental.messages.CompilerMessage
import org.jetbrains.jps.model.artifact.JpsArtifact
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class AGArtifactBuildTaskProvider : ArtifactBuildTaskProvider() {
    override fun createArtifactBuildTasks(artifact: JpsArtifact, buildPhase: ArtifactBuildPhase): List<BuildTask> {
        if(buildPhase == ArtifactBuildPhase.PRE_PROCESSING) {
            val ext: ModuleArtifactExt = artifact.container.getChild(AGPREPROCESSING_ROLE)
            if(ext.cmd.isNotEmpty() && ext.name.isNotEmpty()) {
                return listOf(AgBuildTask(ext))
            }
        }
        return emptyList()
    }
}

class AgBuildTask(val ext: ModuleArtifactExt) : BuildTask() {
    val BUILDER_NAME = "mvn"
    val LOG = Logger.getInstance(AgBuildTask::class.java)

    override fun build(context: CompileContext) {
        val project = context.projectDescriptor.project

        try {
            val commandLine = listOf("bash", "-c", ext.cmd)
            val process = ProcessBuilder(commandLine).start()
            val commandLineString = StringUtil.join(commandLine, " ")
            if (LOG.isDebugEnabled()) {
                LOG.debug("Starting ant target:" + commandLineString)
            }
            val handler = BaseOSProcessHandler(process, commandLineString, null)
            val hasErrors = AtomicBoolean()
            val errorOutput = StringBuilder()
            handler.addProcessListener(object : ProcessAdapter() {
                override fun onTextAvailable(event: ProcessEvent?, outputType: Key<*>?) {
                    if (outputType === ProcessOutputTypes.STDERR) {
                        errorOutput.append(event!!.text)
                    }
                }

                override fun processTerminated(event: ProcessEvent?) {
                    val exitCode = event!!.exitCode
                    if (exitCode != 0) {
                        context.processMessage(CompilerMessage(BUILDER_NAME, BuildMessage.Kind.ERROR, errorOutput.toString()))
                        hasErrors.set(true)
                    }
                }
            })
            handler.startNotify()
            handler.waitFor()
            if (hasErrors.get()) {
                throw StopBuildException()
            }
        } catch (e: IOException) {
            throw ProjectBuildException(e)
        }
    }

    private fun reportError(context: CompileContext, text: String) {
        context.processMessage(CompilerMessage(BUILDER_NAME, BuildMessage.Kind.ERROR,
                "Cannot run '$ext' target: $text"))
    }

}
