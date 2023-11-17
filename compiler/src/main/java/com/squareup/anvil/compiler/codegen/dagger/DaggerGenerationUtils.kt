package com.squareup.anvil.compiler.codegen.dagger

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.anvil.compiler.assistedFqName
import com.squareup.anvil.compiler.codegen.ksp.KspAnvilException
import com.squareup.anvil.compiler.codegen.ksp.isAnnotationPresent
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeArgument
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.KSValueParameter
import com.squareup.anvil.compiler.daggerDoubleCheckFqNameString
import com.squareup.anvil.compiler.daggerLazyFqName
import com.squareup.anvil.compiler.injectFqName
import com.squareup.anvil.compiler.internal.capitalize
import com.squareup.anvil.compiler.internal.reference.AnvilCompilationExceptionClassReference
import com.squareup.anvil.compiler.internal.reference.AnvilCompilationExceptionPropertyReference
import com.squareup.anvil.compiler.internal.reference.ClassReference
import com.squareup.anvil.compiler.internal.reference.MemberFunctionReference
import com.squareup.anvil.compiler.internal.reference.MemberPropertyReference
import com.squareup.anvil.compiler.internal.reference.ParameterReference
import com.squareup.anvil.compiler.internal.reference.TypeParameterReference
import com.squareup.anvil.compiler.internal.reference.TypeReference
import com.squareup.anvil.compiler.internal.reference.Visibility.PRIVATE
import com.squareup.anvil.compiler.internal.reference.allSuperTypeClassReferences
import com.squareup.anvil.compiler.internal.reference.argumentAt
import com.squareup.anvil.compiler.internal.reference.asClassName
import com.squareup.anvil.compiler.internal.reference.generateClassName
import com.squareup.anvil.compiler.internal.withJvmSuppressWildcardsIfNeeded
import com.squareup.anvil.compiler.jvmFieldFqName
import com.squareup.anvil.compiler.jvmSuppressWildcardsFqName
import com.squareup.anvil.compiler.providerFqName
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.jvm.jvmSuppressWildcards
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.toTypeVariableName
import dagger.Lazy
import dagger.internal.ProviderOfLazy
import javax.inject.Provider

internal fun TypeName.wrapInProvider(): ParameterizedTypeName {
  return Provider::class.asClassName().parameterizedBy(this)
}

internal fun TypeName.wrapInLazy(): ParameterizedTypeName {
  return Lazy::class.asClassName().parameterizedBy(this)
}

internal fun List<ParameterReference>.mapToConstructorParameters(): List<ConstructorParameter> {
  return fold(listOf()) { acc, callableReference ->
    acc + callableReference.toConstructorParameter(callableReference.name.uniqueParameterName(acc))
  }
}
internal fun List<KSValueParameter>.mapKSPToConstructorParameters(): List<ConstructorParameter> {
  return fold(listOf()) { acc, callableReference ->
    acc + callableReference.toConstructorParameter(callableReference.name!!.asString().uniqueParameterName(acc))
  }
}

private fun ParameterReference.toConstructorParameter(
  uniqueName: String,
): ConstructorParameter {
  val type = type()

  val isWrappedInProvider = type.asClassReferenceOrNull()?.fqName == providerFqName
  val isWrappedInLazy = type.asClassReferenceOrNull()?.fqName == daggerLazyFqName
  val isLazyWrappedInProvider = isWrappedInProvider &&
    type.unwrappedTypes.first().asClassReferenceOrNull()?.fqName == daggerLazyFqName

  val typeName = when {
    isLazyWrappedInProvider -> type.unwrappedTypes.first().unwrappedTypes.first()
    isWrappedInProvider || isWrappedInLazy -> type.unwrappedTypes.first()
    else -> type
  }.asTypeName().withJvmSuppressWildcardsIfNeeded(this, type)

  val assistedAnnotation = annotations.singleOrNull { it.fqName == assistedFqName }

  val assistedIdentifier = assistedAnnotation
    ?.argumentAt("value", 0)
    ?.value()
    ?: ""

  return ConstructorParameter(
    name = uniqueName,
    originalName = name,
    typeName = typeName,
    providerTypeName = typeName.wrapInProvider(),
    lazyTypeName = typeName.wrapInLazy(),
    isWrappedInProvider = isWrappedInProvider,
    isWrappedInLazy = isWrappedInLazy,
    isLazyWrappedInProvider = isLazyWrappedInProvider,
    isAssisted = assistedAnnotation != null,
    assistedIdentifier = assistedIdentifier
  )
}

private fun KSValueParameter.toConstructorParameter(
  uniqueName: String
): ConstructorParameter {
  val resolvedType = type.resolve()
  val qualifiedTypeName = resolvedType.declaration.qualifiedName?.asString()

  //val typeArguments: List<KSTypeReference?> = type.arguments.map {it.type}
  val typeReferences: List<KSTypeArgument> = resolvedType.arguments

  val isWrappedInProvider = qualifiedTypeName == providerFqName.asString()
  val isWrappedInLazy = qualifiedTypeName == daggerLazyFqName.asString()
  val isLazyWrappedInProvider = isWrappedInProvider &&
    typeReferences.firstOrNull()?.type?.resolve()?.declaration?.qualifiedName?.asString() == daggerLazyFqName.asString()

  val typeName = when {
    isLazyWrappedInProvider -> typeReferences.firstOrNull()?.type?.resolve()?.arguments?.map{it.type}?.firstOrNull()!!.toTypeName()
    isWrappedInProvider || isWrappedInLazy -> resolvedType.declaration.typeParameters.firstOrNull()!!.toTypeVariableName()
    //This didnt work before... typeReferences.firstOrNull()!!.toTypeName()
    else ->   try {
      resolvedType.toTypeName()
    } catch (e: Exception) {
      (resolvedType.declaration as KSTypeParameter).toTypeVariableName()
    }
  }.withJvmSuppressWildcardsIfNeededKSP(this, resolvedType)

  val assistedAnnotation = this.annotations.singleOrNull() { it.shortName.toString() == assistedFqName.toString()}

  /*
  val assistedIdentifier = assistedAnnotation
    ?.argumentAt("value", 0)
    ?.value()
    ?: ""
   */

  return ConstructorParameter(
    name = uniqueName,
    originalName = name!!.asString(),
    typeName = typeName,
    providerTypeName = typeName.wrapInProvider(),
    lazyTypeName = typeName.wrapInLazy(),
    isWrappedInProvider = isWrappedInProvider,
    isWrappedInLazy = isWrappedInLazy,
    isLazyWrappedInProvider = isLazyWrappedInProvider,
    isAssisted = assistedAnnotation != null,
    assistedIdentifier = "" //TODO this should be assistedIdentifier
  )
}

fun TypeName.withJvmSuppressWildcardsIfNeededKSP(
  valueParameter: KSValueParameter,
  type: KSType
): TypeName {
  // If the parameter is annotated with @JvmSuppressWildcards, then add the annotation
  // to our type so that this information is forwarded when our Factory is compiled.
  val hasJvmSuppressWildcards = valueParameter.isAnnotationPresent(jvmSuppressWildcardsFqName.asString())

  // Add the @JvmSuppressWildcards annotation even for simple generic return types like
  // Set<String>. This avoids some edge cases where Dagger chokes.
  // reference for KSP: https://github.com/google/ksp/issues/12
  val isGenericType = type.declaration.typeParameters.isNotEmpty()

  // Same for functions.
  val isFunctionType = type.isFunctionType

  return when {
    hasJvmSuppressWildcards || isGenericType -> this.jvmSuppressWildcards()
    isFunctionType -> this.jvmSuppressWildcards()
    else -> this
  }
}

internal fun FunSpec.Builder.addMemberInjection(
  memberInjectParameters: List<MemberInjectParameter>,
  instanceName: String
): FunSpec.Builder = apply {

  memberInjectParameters.forEach { parameter ->

    val functionName = "inject${parameter.accessName.capitalize()}"

    val param = when {
      parameter.isLazyWrappedInProvider ->
        "${ProviderOfLazy::class.qualifiedName}.create(${parameter.name})"
      parameter.isWrappedInProvider -> parameter.name
      parameter.isWrappedInLazy ->
        "$daggerDoubleCheckFqNameString.lazy(${parameter.name})"
      else -> parameter.name + ".get()"
    }

    addStatement("%T.$functionName($instanceName, $param)", parameter.memberInjectorClassName)
  }
}

/**
 * Returns all member-injected parameters for the receiver class *and any superclasses*.
 *
 * We use Psi whenever possible, to support generated code.
 *
 * Order is important. Dagger expects the properties of the most-upstream class to be listed first
 * in a factory's constructor.
 *
 * Given the hierarchy:
 * Impl -> Middle -> Base
 * The order of dependencies in `Impl_Factory`'s constructor should be:
 * Base -> Middle -> Impl
 */
internal fun ClassReference.memberInjectParameters(): List<MemberInjectParameter> {
  return allSuperTypeClassReferences(includeSelf = true)
    .filterNot { it.isInterface() }
    .toList()
    .foldRight(listOf()) { classReference, acc ->
      acc + classReference.declaredMemberInjectParameters(acc, this)
    }
}

/**
 * @param superParameters injected parameters from any super-classes, regardless of whether they're
 * overridden by the receiver class
 * @return the member-injected parameters for this class only, not including any super-classes
 */
private fun ClassReference.declaredMemberInjectParameters(
  superParameters: List<Parameter>,
  implementingClass: ClassReference
): List<MemberInjectParameter> {
  return properties
    .filter { it.isAnnotatedWith(injectFqName) }
    .filter { it.visibility() != PRIVATE }
    .fold(listOf()) { acc, property ->
      val uniqueName = property.name.uniqueParameterName(superParameters, acc)
      acc + property.toMemberInjectParameter(
        uniqueName = uniqueName,
        implementingClass = implementingClass
      )
    }
}

/**
 * Returns all member-injected parameters for the receiver class *and any superclasses*.
 *
 * We use Psi whenever possible, to support generated code.
 *
 * Order is important. Dagger expects the properties of the most-upstream class to be listed first
 * in a factory's constructor.
 *
 * Given the hierarchy:
 * Impl -> Middle -> Base
 * The order of dependencies in `Impl_Factory`'s constructor should be:
 * Base -> Middle -> Impl
 */
internal fun KSClassDeclaration.memberInjectParameters(): List<MemberInjectParameter> {
  return getAllProperties()
    .filter { it.isAnnotationPresent(injectFqName.asString()) }
    .filter { it.getVisibility() != Visibility.PRIVATE }
    .fold(listOf()) { acc, property ->
      val uniqueName = property.simpleName.asString().uniqueParameterName(acc)
      acc + property.toMemberInjectParameter(
        uniqueName = uniqueName,
        implementingClass = this,
      )
    }
}



/**
 * Converts the parameter list to comma separated argument list that can be used to call other
 * functions, e.g.
 * ```
 * [param0: String, param1: Int] -> "param0, param1"
 * ```
 * [asProvider] allows you to decide if each parameter is wrapped in a `Provider` interface. If
 * true, then the `get()` function will be called for the provider parameter. If false, then
 * then always only the parameter name will used in the argument list:
 * ```
 * "param0.get()" vs "param0"
 * ```
 * Set [includeModule] to true if a Dagger module instance is part of the argument list.
 */
internal fun List<Parameter>.asArgumentList(
  asProvider: Boolean,
  includeModule: Boolean
): String {
  return this
    .let { list ->
      if (asProvider) {
        list.map { parameter ->
          when {
            parameter.isLazyWrappedInProvider ->
              "${ProviderOfLazy::class.qualifiedName}.create(${parameter.name})"
            parameter.isWrappedInProvider -> parameter.name
            // Normally Dagger changes Lazy<Type> parameters to a Provider<Type> (usually the
            // container is a joined type), therefore we use `.lazy(..)` to convert the Provider
            // to a Lazy. Assisted parameters behave differently and the Lazy type is not changed
            // to a Provider and we can simply use the parameter name in the argument list.
            parameter.isWrappedInLazy && parameter.isAssisted -> parameter.name
            parameter.isWrappedInLazy -> "$daggerDoubleCheckFqNameString.lazy(${parameter.name})"
            parameter.isAssisted -> parameter.name
            else -> "${parameter.name}.get()"
          }
        }
      } else list.map { it.name }
    }
    .let {
      if (includeModule) {
        val result = it.toMutableList()
        result.add(0, "module")
        result.toList()
      } else {
        it
      }
    }
    .joinToString()
}

private fun MemberPropertyReference.toMemberInjectParameter(
  uniqueName: String,
  implementingClass: ClassReference
): MemberInjectParameter {
  if (
    !isLateinit() &&
    !isAnnotatedWith(jvmFieldFqName) &&
    setterAnnotations.none { it.fqName == injectFqName }
  ) {
    // Technically this works with Anvil and we could remove this check. But we prefer consistency
    // with Dagger.
    throw AnvilCompilationExceptionPropertyReference(
      propertyReference = this,
      message = "Dagger does not support injection into private fields. Either use a " +
        "'lateinit var' or '@JvmField'."
    )
  }

  val originalName = name
  val type = type()

  val isWrappedInProvider = type.asClassReferenceOrNull()?.fqName == providerFqName
  val isWrappedInLazy = type.asClassReferenceOrNull()?.fqName == daggerLazyFqName
  val isLazyWrappedInProvider = isWrappedInProvider &&
    type.unwrappedTypes.first().asClassReferenceOrNull()?.fqName == daggerLazyFqName

  val unwrappedType = when {
    isLazyWrappedInProvider -> type.unwrappedTypes.first().unwrappedTypes.first()
    isWrappedInProvider || isWrappedInLazy -> type.unwrappedTypes.first()
    else -> type
  }
  val typeName = unwrappedType.asTypeName().withJvmSuppressWildcardsIfNeeded(this, type)
  val resolvedTypeName = if (unwrappedType.isGenericExcludingTypeAliases()) {
    unwrappedType.asClassReferenceOrNull()
      ?.asClassName()
      ?.optionallyParameterizedByNames(
        unwrappedType.unwrappedTypes.mapNotNull {
          it.resolveGenericTypeNameOrNull(implementingClass)
        }
      )
      ?.withJvmSuppressWildcardsIfNeeded(this, unwrappedType)
  } else {
    null
  }

  val assistedAnnotation = annotations.singleOrNull { it.fqName == assistedFqName }

  val assistedIdentifier = assistedAnnotation
    ?.argumentAt("value", 0)
    ?.value()
    ?: ""

  val memberInjectorClassName = declaringClass
    .generateClassName(separator = "_", suffix = "_MembersInjector")
    .relativeClassName
    .asString()

  val memberInjectorClass = ClassName(
    declaringClass.packageFqName.asString(), memberInjectorClassName
  )

  val isSetterInjected = this.setterAnnotations.any { it.fqName == injectFqName }

  // setter delegates require a "set" prefix for their inject function
  val accessName = if (isSetterInjected) {
    "set${originalName.capitalize()}"
  } else {
    originalName
  }

  val qualifierAnnotations = annotations
    .filter { it.isQualifier() }
    .map { it.toAnnotationSpec() }

  val providerTypeName = typeName.wrapInProvider()

  return MemberInjectParameter(
    name = uniqueName,
    originalName = originalName,
    typeName = typeName,
    providerTypeName = providerTypeName,
    lazyTypeName = typeName.wrapInLazy(),
    isWrappedInProvider = isWrappedInProvider,
    isWrappedInLazy = isWrappedInLazy,
    isLazyWrappedInProvider = isLazyWrappedInProvider,
    isAssisted = assistedAnnotation != null,
    assistedIdentifier = assistedIdentifier,
    memberInjectorClassName = memberInjectorClass,
    isSetterInjected = isSetterInjected,
    accessName = accessName,
    qualifierAnnotationSpecs = qualifierAnnotations,
    injectedFieldSignature = fqName,
    resolvedProviderTypeName = resolvedTypeName?.wrapInProvider() ?: providerTypeName
  )
}

private fun KSPropertyDeclaration.toMemberInjectParameter(
  uniqueName: String,
  implementingClass: KSClassDeclaration
): MemberInjectParameter {
  if (
    !modifiers.contains(Modifier.LATEINIT) &&
    !isAnnotationPresent(jvmFieldFqName.asString()) &&
    setter?.isAnnotationPresent(injectFqName.asString()) != true
  ) {
    // Technically this works with Anvil and we could remove this check. But we prefer consistency
    // with Dagger.
    throw KspAnvilException(
      node = this,
      message = "Dagger does not support injection into private fields. Either use a " +
        "'lateinit var' or '@JvmField'."
    )
  }

  val originalName = simpleName.asString()
  val resolvedType = type.resolve()
  val qualifiedTypeName = resolvedType.declaration.qualifiedName?.asString()

  val isWrappedInProvider = qualifiedTypeName == providerFqName.asString()
  val isWrappedInLazy = qualifiedTypeName == daggerLazyFqName.asString()
  val isLazyWrappedInProvider = isWrappedInProvider &&
    typeParameters.first().qualifiedName?.asString() == daggerLazyFqName.asString()

  val unwrappedType = when {
    isLazyWrappedInProvider -> typeParameters.first().typeParameters.first().toTypeVariableName()
    isWrappedInProvider || isWrappedInLazy -> typeParameters.first().toTypeVariableName()
    else -> resolvedType.toTypeName()
  }
  /*
  val typeName = unwrappedType.withJvmSuppressWildcardsIfNeeded(this, type)
  val resolvedTypeName = if (unwrappedType.isGenericExcludingTypeAliases()) {
    unwrappedType.asClassReferenceOrNull()
      ?.asClassName()
      ?.optionallyParameterizedByNames(
        unwrappedType.unwrappedTypes.mapNotNull {
          it.resolveGenericTypeNameOrNull(implementingClass)
        }
      )
      ?.withJvmSuppressWildcardsIfNeeded(this, unwrappedType)
  } else {
    null
  }

  val assistedAnnotation = annotations.singleOrNull { it.fqName == assistedFqName }

  val assistedIdentifier = assistedAnnotation
    ?.argumentAt("value", 0)
    ?.value()
    ?: ""

  val memberInjectorClassName = declaringClass
    .generateClassName(separator = "_", suffix = "_MembersInjector")
    .relativeClassName
    .asString()

  val memberInjectorClass = ClassName(
    declaringClass.packageFqName.asString(), memberInjectorClassName
  )

  val isSetterInjected = this.setterAnnotations.any { it.fqName == injectFqName }

  // setter delegates require a "set" prefix for their inject function
  val accessName = if (isSetterInjected) {
    "set${originalName.capitalize()}"
  } else {
    originalName
  }

  val qualifierAnnotations = annotations
    .filter { it.isQualifier() }
    .map { it.toAnnotationSpec() }

  val providerTypeName = typeName.wrapInProvider()
   */

  val assistedAnnotation = null
  val assistedIdentifier = ""
  val memberInjectorClass = null
  val isSetterInjected = null
  val qualifierAnnotations = null
  val fqName = null
  val resolvedTypeName = null
  val providerTypeName = null

  return MemberInjectParameter(
    name = uniqueName,
    originalName = originalName,
    typeName = INT,
    providerTypeName = providerTypeName!!,
    lazyTypeName = INT.wrapInLazy(),
    isWrappedInProvider = isWrappedInProvider,
    isWrappedInLazy = isWrappedInLazy,
    isLazyWrappedInProvider = isLazyWrappedInProvider,
    isAssisted = assistedAnnotation != null,
    assistedIdentifier = assistedIdentifier,
    memberInjectorClassName = memberInjectorClass!!,
    isSetterInjected = isSetterInjected!!,
    accessName = "accessName",
    qualifierAnnotationSpecs = qualifierAnnotations!!,
    injectedFieldSignature = fqName!!,
    resolvedProviderTypeName = resolvedTypeName?.wrapInProvider() ?: providerTypeName!!
  )
}

private fun TypeReference.isGenericExcludingTypeAliases(): Boolean {
  // A TypeReference for 'typealias StringList = List<String> would still show up as generic but
  // would have no unwrapped types available (String).
  return isGenericType() && unwrappedTypes.isNotEmpty()
}

private fun ClassName.optionallyParameterizedByNames(
  typeNames: List<TypeName>
): TypeName {
  return if (typeNames.isEmpty()) {
    this
  } else {
    parameterizedBy(typeNames)
  }
}

internal fun ClassName.optionallyParameterizedBy(
  typeParameters: List<TypeParameterReference>
): TypeName {
  return if (typeParameters.isEmpty()) {
    this
  } else {
    parameterizedBy(typeParameters.map { it.typeVariableName })
  }
}

internal fun assertNoDuplicateFunctions(
  declaringClass: ClassReference,
  functions: Sequence<MemberFunctionReference.Psi>
) {
  // Check for duplicate function names.
  val duplicateFunctions = functions
    .groupBy { it.fqName }
    .filterValues { it.size > 1 }

  if (duplicateFunctions.isNotEmpty()) {
    throw AnvilCompilationExceptionClassReference(
      classReference = declaringClass,
      message = "Cannot have more than one binding method with the same name in " +
        "a single module: ${duplicateFunctions.keys.joinToString()}"
    )
  }
}
