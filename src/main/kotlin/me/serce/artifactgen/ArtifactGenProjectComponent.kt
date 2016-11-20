package me.serce.artifactgen

import com.intellij.ProjectTopics
import com.intellij.lang.ant.config.AntConfiguration
import com.intellij.lang.ant.config.impl.artifacts.AntArtifactPreProcessingPropertiesProvider
import com.intellij.lang.ant.config.impl.artifacts.AntArtifactProperties
import com.intellij.openapi.application.Result
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileTypes.StdFileTypes
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.ModuleAdapter
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.*
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.roots.ui.configuration.DefaultModulesProvider
import com.intellij.packaging.artifacts.ArtifactManager
import com.intellij.packaging.artifacts.ArtifactTemplate
import com.intellij.packaging.elements.ArtifactRootElement
import com.intellij.packaging.elements.CompositePackagingElement
import com.intellij.packaging.elements.PackagingElementFactory
import com.intellij.packaging.impl.artifacts.ArtifactUtil
import com.intellij.packaging.impl.artifacts.PlainArtifactType
import com.intellij.packaging.impl.elements.LibraryPackagingElement
import com.intellij.packaging.impl.elements.ManifestFileUtil
import com.intellij.packaging.impl.elements.ProductionModuleOutputElementType
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.impl.file.PsiDirectoryFactory
import com.intellij.util.CommonProcessors
import com.intellij.util.PathUtil
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.xmlb.XmlSerializerUtil
import java.util.*
import javax.swing.SwingUtilities


data class Options(var enabled: Boolean = false,
                   var modules: List<String> = emptyList(),
                   var preprocessing: List<ModuleArtifactProps> = emptyList())

@State(name = "artifactgen", storages = arrayOf(Storage(StoragePathMacros.WORKSPACE_FILE)))
class ArtifactGenProjectComponent(val project: Project,
                                  val moduleManager: ModuleManager,
                                  val artifactManager: ArtifactManager) :
        AbstractProjectComponent(project), PersistentStateComponent<Options> {

    val LOG = Logger.getInstance(javaClass)
    val modulesProvider = DefaultModulesProvider.createForProject(project)
    val antConfiguration = AntConfiguration.getInstance(project)
    var connection: MessageBusConnection? = null
    var options = Options()

    override fun getState() = options
    override fun loadState(state: Options) = XmlSerializerUtil.copyBean(state, options)


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

                    if(includePreprocessing.isNotEmpty()) {
                        val preprModule = includePreprocessing.first()
                        val harness = moduleManager.findModuleByName(preprModule.name)?.moduleFile?.parent
                        if (harness != null && harness.exists()) {
                            if (antConfiguration.buildFiles.isEmpty()) {
                                val psiFile = PsiFileFactory.getInstance(project).createFileFromText("build.xml", StdFileTypes.XML,
                                        """
<project name="harness">
  <target name="file-checks">
    <available file="target/classes/static.zip"  property="first.static"/>
  </target>

  <target name="process" depends="file-checks" unless="first.static">
    <exec dir="." executable="sh">
      <arg line="-c 'mvn process-classes'"/>
    </exec>
  </target>
</project>
""")
                                val dir = PsiDirectoryFactory.getInstance(project).createDirectory(harness)
                                dir.add(psiFile)
                                antConfiguration.addBuildFile(dir.findFile("build.xml")!!.virtualFile)
                            }

                            val harnessXml = harness.findChild("build.xml")!!
                            val antPreprocessingProvier = AntArtifactPreProcessingPropertiesProvider.getInstance()
                            val props = antPreprocessingProvier.createProperties(artifact.artifactType) as AntArtifactProperties
                            props.isEnabled = true
                            props.targetName = "process"
                            props.fileUrl = harnessXml.url
                            artifact.setProperties(antPreprocessingProvier, props)
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