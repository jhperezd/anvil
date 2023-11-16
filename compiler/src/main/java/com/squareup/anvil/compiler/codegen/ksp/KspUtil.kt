package com.squareup.anvil.compiler.codegen.ksp

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import kotlin.reflect.KClass

/**
 * Returns a sequence of [KSAnnotations][KSAnnotation] of the given [annotationKClass] type.
 */
internal fun <T : Annotation> KSAnnotated.getKSAnnotationsByType(
  annotationKClass: KClass<T>
): Sequence<KSAnnotation> {
  return annotations.filter {
    it.shortName.getShortName() == annotationKClass.simpleName &&
      it.annotationType.resolve()
      .declaration.qualifiedName?.asString() == annotationKClass.qualifiedName
  }
}

/**
 * Returns a sequence of [KSAnnotations][KSAnnotation] of the given [qualifiedName].
 */
internal fun KSAnnotated.getKSAnnotationsByQualifiedName(
  qualifiedName: String
): Sequence<KSAnnotation> {
  val simpleName = qualifiedName.substringAfterLast(".")
  return annotations.filter {
    it.shortName.getShortName() == simpleName &&
      it.annotationType.resolve()
      .declaration.qualifiedName?.asString() == qualifiedName
  }
}

internal fun KSAnnotated.isAnnotationPresent(qualifiedName: String): Boolean =
  getKSAnnotationsByQualifiedName(qualifiedName).firstOrNull() != null

internal fun KSTypeAlias.findActualType(): KSClassDeclaration {
  val resolvedType = this.type.resolve().declaration
  return if (resolvedType is KSTypeAlias) {
    resolvedType.findActualType()
  } else {
    resolvedType as KSClassDeclaration
  }
}

internal fun KSTypeReference.findActualType(): KSClassDeclaration {
  val resolvedType = this.resolve().declaration
  return if (resolvedType is KSTypeAlias) {
    resolvedType.findActualType()
  } else {
    resolvedType as KSClassDeclaration
  }
}
