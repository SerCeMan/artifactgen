package me.serce.artifactgen

import com.intellij.util.xmlb.SkipDefaultValuesSerializationFilters
import com.intellij.util.xmlb.XmlSerializer
import org.jdom.Element
import org.jetbrains.jps.ant.model.artifacts.JpsAntArtifactExtension
import org.jetbrains.jps.ant.model.impl.artifacts.AntArtifactExtensionProperties
import org.jetbrains.jps.ant.model.impl.artifacts.JpsAntArtifactExtensionImpl
import org.jetbrains.jps.model.JpsElement
import org.jetbrains.jps.model.JpsElementChildRole
import org.jetbrains.jps.model.ex.JpsCompositeElementBase
import org.jetbrains.jps.model.ex.JpsElementChildRoleBase
import org.jetbrains.jps.model.serialization.JpsModelSerializerExtension
import org.jetbrains.jps.model.serialization.artifact.JpsArtifactExtensionSerializer


interface ModuleArtifactPropDef {
    val name: String
    val cmd: String
}

data class ModuleArtifactProps(override val name: String = "",
                               override val cmd: String = "") : ModuleArtifactPropDef

interface ModuleArtifactExt : ModuleArtifactPropDef, JpsElement

val AGPREPROCESSING_ROLE: JpsElementChildRole<ModuleArtifactExt> = JpsElementChildRoleBase.create<ModuleArtifactExt>("artifactgen preprocessing")

class ModuleArtifactExtImpl(val props: ModuleArtifactPropDef) :
        JpsCompositeElementBase<ModuleArtifactExtImpl>(),
        ModuleArtifactPropDef by props,
        ModuleArtifactExt {
    override fun createCopy() = ModuleArtifactExtImpl(this)
}


class AGModelSerializerExtension : JpsModelSerializerExtension() {
    override fun getArtifactExtensionSerializers(): List<JpsArtifactExtensionSerializer<*>> {
        return listOf(AGJpsArtifactProjectSerializer(AGPREPROCESSING_ROLE))
    }


}

class AGJpsArtifactProjectSerializer(role: JpsElementChildRole<ModuleArtifactExt>) :
        JpsArtifactExtensionSerializer<ModuleArtifactExt>("module-preprocessing", role) {
    override fun loadExtension(optionsTag: Element?): ModuleArtifactExt {
        return ModuleArtifactExtImpl(optionsTag?.let {
            XmlSerializer.deserialize(optionsTag, ModuleArtifactProps::class.java)
        } ?: ModuleArtifactProps())
    }

    override fun saveExtension(extension: ModuleArtifactExt, optionsTag: Element) {
        XmlSerializer.serializeInto(extension, optionsTag, SkipDefaultValuesSerializationFilters())
    }
}