package com.squareup.anvil.conventions.utils

import com.rickbusarow.kgx.getOrPut
import org.gradle.api.NamedDomainObjectCollectionSchema.NamedDomainObjectSchema
import org.gradle.api.tasks.TaskCollection
import org.jetbrains.kotlin.gradle.plugin.extraProperties

/** @throws IllegalArgumentException if there are multiple tasks of that name when ignoring its case */
internal fun TaskCollection<*>.namedOrNull(taskName: String): NamedDomainObjectSchema? {

  // This will typically be a 1:1 grouping,
  // but Gradle does allow you to re-use task names with different capitalization,
  // like 'foo' and 'Foo'.
  val namesLowercase: Map<String, List<NamedDomainObjectSchema>> =
    extraProperties.getOrPut("taskNamesLowercaseToSchema") {
      collectionSchema.elements.groupBy { it.name.lowercase() }
    }

  val taskNameLowercase = taskName.lowercase()

  // All tasks that match the lowercase name
  val lowercaseMatches = namesLowercase[taskNameLowercase] ?: return null

  // The task with the same case as the requested name, or null
  val exactMatch = lowercaseMatches.singleOrNull { it.name == taskName }

  if (exactMatch != null) {
    return exactMatch
  }

  require(lowercaseMatches.size == 1) {
    "Task name '$taskName' is ambiguous.  " +
      "It matches multiple tasks: ${lowercaseMatches.map { it.name }}"
  }

  return lowercaseMatches.single()
}
