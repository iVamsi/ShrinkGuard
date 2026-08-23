package com.shrinkguard.gradle

import org.gradle.api.Action
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import javax.inject.Inject

open class RuleLintOptions @Inject constructor(objects: ObjectFactory) {
    val failOnToxicFlags: Property<Boolean> = objects.property(Boolean::class.java).convention(true)
    val failOnOverbroadRules: Property<Boolean> = objects.property(Boolean::class.java).convention(false)
    val allowlistRules: SetProperty<String> = objects.setProperty(String::class.java).convention(emptySet())
}

open class ShrinkGuardExtension @Inject constructor(objects: ObjectFactory) {
    val baselineFile: RegularFileProperty = objects.fileProperty()
    val rulesFiles: ConfigurableFileCollection = objects.fileCollection()
    val ruleLint: RuleLintOptions = objects.newInstance(RuleLintOptions::class.java)

    fun ruleLint(action: Action<RuleLintOptions>) {
        action.execute(ruleLint)
    }
}
