package me.serce.artifactgen

import com.intellij.ProjectTopics
import com.intellij.openapi.application.Result
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.AbstractProjectComponent
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.ModuleAdapter
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.*
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.roots.ui.configuration.DefaultModulesProvider
import com.intellij.packaging.artifacts.*
import com.intellij.packaging.elements.ArtifactRootElement
import com.intellij.packaging.elements.CompositePackagingElement
import com.intellij.packaging.elements.PackagingElementFactory
import com.intellij.packaging.impl.artifacts.ArtifactUtil
import com.intellij.packaging.impl.artifacts.PlainArtifactType
import com.intellij.packaging.impl.elements.LibraryPackagingElement
import com.intellij.packaging.impl.elements.ProductionModuleOutputElementType
import com.intellij.packaging.ui.ArtifactEditorContext
import com.intellij.packaging.ui.ArtifactPropertiesEditor
import com.intellij.util.CommonProcessors
import com.intellij.util.PathUtil
import com.intellij.util.messages.MessageBusConnection
import java.io.File
import java.util.*
import javax.swing.JLabel
import javax.swing.SwingUtilities


class Options() {
    var enabled: Boolean = false
    var modules: List<ModuleDef> = arrayListOf()
    var preprocessing: List<ModuleArtifactProps> = arrayListOf()
}

class ModuleDef {
    var name: String = ""
    var output: String? = null
}

@State(name = "ArtifactGen", storages = arrayOf(Storage("artifactgen.xml")))
class AGProjectComponent(val project: Project,
                         val projectRootManager: ProjectRootManager,
                         val moduleManager: ModuleManager,
                         val artifactManager: ArtifactManager,
                         val factory: PackagingElementFactory) :
        AbstractProjectComponent(project), PersistentStateComponent<Options> {

    val LOG = Logger.getInstance(javaClass)
    val modulesProvider = DefaultModulesProvider.createForProject(project)
    var connection: MessageBusConnection? = null
    var options = Options()

    override fun getState() = options
    override fun loadState(state: Options) {
        options = state
    }


    override fun projectOpened() {
        fun rebuildArtifact() {
            if (state.enabled) {
                for (moduleDef in state.modules) {
                    val module = moduleManager.modules.find { it.name == moduleDef.name }
                    if(module != null) {
                        rebuildArtifactDef(moduleDef, module)
                    }
                }
            }
        }

        val conn = project.messageBus.connect(project)
        conn.subscribe(ProjectTopics.MODULES, object : ModuleAdapter() {
            override fun moduleAdded(project: Project, module: Module) = rebuildArtifact()
        })
        conn.subscribe(ProjectTopics.PROJECT_ROOTS, object : ModuleRootAdapter() {
            override fun rootsChanged(event: ModuleRootEvent) = rebuildArtifact()
        })
        connection = conn
    }

    override fun projectClosed() {
        connection?.disconnect()
    }

    private fun rebuildArtifactDef(def: ModuleDef, module: Module) {
        val name = module.name

        val archive = factory.createArchive(module.jarName())
        val orderEnumerator = projectRootManager.orderEntries(listOf(module)).productionOnly()
        val includePreprocessing = ArrayList<ModuleArtifactProps>()
        val root = factory.createArtifactRootElement()
        val libraries = HashSet<Library>()

        orderEnumerator.using(modulesProvider)
                .withoutSdk()
                .runtimeOnly()
                .recursively().apply {

            forEachLibrary(CommonProcessors.CollectProcessor(libraries))

            forEachModule { module ->
                if (ProductionModuleOutputElementType.ELEMENT_TYPE.isSuitableModule(modulesProvider, module)) {
                    val moduleArchive = factory.createArchive(module.jarName())
                    moduleArchive.addOrFindChild(factory.createModuleOutput(module))
                    root.addOrFindChild(moduleArchive)

                    state.preprocessing
                            .filter { it.name == module.name }
                            .forEach {
                                includePreprocessing.add(it)
                            }
                }
                true
            }
        }

        root.addOrFindChild(archive)
        addLibraries(libraries, root, archive)

        val artifactName = "$name:jar"
        val config = ArtifactTemplate.NewArtifactConfiguration(root, artifactName, PlainArtifactType.getInstance())

        SwingUtilities.invokeLater {
            object : WriteAction<Unit>() {
                override fun run(result: Result<Unit>) {
                    val artifactModel = artifactManager.createModifiableModel()
                    artifactModel.artifacts
                            .filter { it.name == artifactName }
                            .forEach { artifactModel.removeArtifact(it) }
                    val artifact = artifactModel.addArtifact(config.artifactName, config.artifactType, config.rootElement)
                    artifact.isBuildOnMake = true

                    val outputPath = def.output ?: CompilerModuleExtension.getInstance(module)
                            ?.compilerOutputPointer
                            ?.url
                            ?.let { url -> "${File(url).parentFile.absolutePath}/dependency" }
                    if (outputPath == null) {
                        return
                    } else {
                        File(outputPath).mkdirs()
                    }
                    artifact.outputPath = outputPath

                    if (includePreprocessing.isNotEmpty()) {
                        val preprModule = includePreprocessing.first()
                        val harness = moduleManager.findModuleByName(preprModule.name)?.moduleFile?.parent
                        if (harness != null && harness.exists()) {
                            val propsProvider = AGArtifactPropertiesProvider.getInstance()
                            artifact.setProperties(propsProvider, propsProvider.createProperties(artifact.artifactType).apply {
                                this.name = preprModule.name
                                this.cmd = preprModule.cmd
                            })
                        }
                    }

                    artifactModel.commit()
                }
            }.execute()
        }
    }

    private fun addLibraries(libraries: Set<Library>,
                             root: ArtifactRootElement<*>,
                             archive: CompositePackagingElement<*>) {
        for (library in libraries) {
            if (LibraryPackagingElement.getKindForLibrary(library).containsDirectoriesWithClasses()) {
                for (classesRoot in library.getFiles(OrderRootType.CLASSES)) {
                    if (classesRoot.isInLocalFileSystem) {
                        val dir = factory.createDirectoryCopyWithParentDirectories(classesRoot.path, "/")
                        archive.addOrFindChild(dir)
                    } else {
                        val child = factory.createFileCopyWithParentDirectories(PathUtil.getLocalFile(classesRoot).path, "/")
                        root.addOrFindChild(child)
                    }
                }
            } else {
                val children = factory.createLibraryElements(library)
                root.addOrFindChildren(children)
            }
        }
    }

    override fun getComponentName() = "ArtifactGen"
}

private fun Module.jarName() = "${ArtifactUtil.suggestArtifactFileName(this.name)}.jar"


class AGArtifactPropertiesProvider : ArtifactPropertiesProvider("ag-preprocessing") {
    companion object {
        fun getInstance() = ArtifactPropertiesProvider.EP_NAME.findExtension(AGArtifactPropertiesProvider::class.java)
    }

    override fun createProperties(artifactType: ArtifactType): AGArtifactProperties {
        return AGArtifactProperties()
    }
}

class AGArtifactProperties(var props: ModuleArtifactProps = ModuleArtifactProps()) :
        ArtifactProperties<ModuleArtifactProps>(), ModuleArtifactPropDef by props {

    override fun createEditor(context: ArtifactEditorContext): ArtifactPropertiesEditor {
        return AGArtifactPropertiesEditor()
    }

    override fun getState() = props

    override fun loadState(state: ModuleArtifactProps) {
        props = state
    }
}

class AGArtifactPropertiesEditor : ArtifactPropertiesEditor() {
    override fun isModified() = false

    override fun getTabName() = "artifactgen"

    override fun disposeUIResources() {
    }

    override fun apply() {
    }

    override fun createComponent() = JLabel("artifactgen label")

    override fun reset() {
    }
}