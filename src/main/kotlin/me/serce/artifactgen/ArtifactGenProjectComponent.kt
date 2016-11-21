package me.serce.artifactgen

import com.intellij.ProjectTopics
import com.intellij.lang.ant.config.AntConfiguration
import com.intellij.openapi.application.Result
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.*
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
import com.intellij.packaging.impl.elements.ManifestFileUtil
import com.intellij.packaging.impl.elements.ProductionModuleOutputElementType
import com.intellij.packaging.ui.ArtifactEditorContext
import com.intellij.packaging.ui.ArtifactPropertiesEditor
import com.intellij.util.CommonProcessors
import com.intellij.util.PathUtil
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.xmlb.XmlSerializerUtil
import java.util.*
import javax.swing.JLabel
import javax.swing.SwingUtilities


class Options() {
    var enabled: Boolean = false
    var modules: List<String> = arrayListOf()
    var preprocessing: List<ModuleArtifactProps> = arrayListOf()
}

@State(name = "ArtifactGen", storages = arrayOf(Storage("artifactgen.xml")))
class ArtifactGenProjectComponent(val project: Project,
                                  val moduleManager: ModuleManager,
                                  val artifactManager: ArtifactManager) :
        AbstractProjectComponent(project), PersistentStateComponent<Options> {

    val LOG = Logger.getInstance(javaClass)
    val modulesProvider = DefaultModulesProvider.createForProject(project)
    var connection: MessageBusConnection? = null
    var options = Options()

    override fun getState(): Options {
        return options
    }

    override fun loadState(state: Options) {
        options = state
    }


    override fun projectOpened() {
        fun rebuildArtifactDef() {
            if (state.enabled) {
                val modules = moduleManager.modules.filter { it.name in state.modules }
                rebuildArtifactDefs(modules)
            }
        }

        val conn = project.messageBus.connect(project)
        conn.subscribe(ProjectTopics.MODULES, object : ModuleAdapter() {
            override fun moduleAdded(project: Project, module: Module) = rebuildArtifactDef()
        })
        conn.subscribe(ProjectTopics.PROJECT_ROOTS, object : ModuleRootAdapter() {
            override fun rootsChanged(event: ModuleRootEvent) = rebuildArtifactDef()
        })
        connection = conn
    }

    override fun projectClosed() {
        connection?.disconnect()
    }

    private fun rebuildArtifactDefs(modules: List<Module>) = modules.forEach { rebuildArtifactDef(it) }

    private fun rebuildArtifactDef(module: Module) {
        val name = module.name

        val factory = PackagingElementFactory.getInstance()
        val archive = factory.createArchive(ArtifactUtil.suggestArtifactFileName(name) + ".jar")

        val orderEnumerator = ProjectRootManager.getInstance(project).orderEntries(moduleManager.modules.toList()).productionOnly()

        val libraries = HashSet<Library>()
        val includePreprocessing = ArrayList<ModuleArtifactProps>()
        val root = factory.createArtifactRootElement()
        val enumerator = orderEnumerator.using(modulesProvider).withoutSdk().runtimeOnly().recursively()
        enumerator.forEachLibrary(CommonProcessors.CollectProcessor(libraries))
        enumerator.forEachModule { module ->
            if (ProductionModuleOutputElementType.ELEMENT_TYPE.isSuitableModule(modulesProvider, module)) {
                val moduleArchive = factory.createArchive(ArtifactUtil.suggestArtifactFileName(module.name) + ".jar")
                moduleArchive.addOrFindChild(factory.createModuleOutput(module))
                root.addOrFindChild(moduleArchive)

                state.preprocessing.filter { it.name == module.name }.forEach {
                    includePreprocessing.add(it)
                }
            }
            true
        }

        val artifactName = name + ":jar"

        val classpath = ArrayList<String>()
        root.addOrFindChild(archive)
        addLibraries(libraries, root, archive, classpath)
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
                    artifact.outputPath = "${CompilerModuleExtension.getInstance(module)?.compilerOutputPath?.parent?.path ?: throw IllegalArgumentException()}/dependency"

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

    private fun addLibraries(libraries: Set<Library>, root: ArtifactRootElement<*>, archive: CompositePackagingElement<*>,
                             classpath: MutableList<String>) {
        val factory = PackagingElementFactory.getInstance()
        for (library in libraries) {
            if (LibraryPackagingElement.getKindForLibrary(library).containsDirectoriesWithClasses()) {
                for (classesRoot in library.getFiles(OrderRootType.CLASSES)) {
                    if (classesRoot.isInLocalFileSystem) {
                        archive.addOrFindChild(factory.createDirectoryCopyWithParentDirectories(classesRoot.path, "/"))
                    } else {
                        val child = factory.createFileCopyWithParentDirectories(PathUtil.getLocalFile(classesRoot).path, "/")
                        root.addOrFindChild(child)
                        classpath.addAll(ManifestFileUtil.getClasspathForElements(listOf(child), artifactManager.resolvingContext, PlainArtifactType.getInstance()))
                    }
                }

            } else {
                val children = factory.createLibraryElements(library)
                classpath.addAll(ManifestFileUtil.getClasspathForElements(children, artifactManager.resolvingContext, PlainArtifactType.getInstance()))
                root.addOrFindChildren(children)
            }
        }
    }

    override fun getComponentName() = "ArtifactGen"
}


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