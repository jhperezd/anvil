package com.squareup.anvil.compiler.codegen.dagger

import com.google.auto.service.AutoService
import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclarationContainer
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.Visibility as KspVisibility
import com.squareup.anvil.compiler.api.AnvilApplicabilityChecker
import com.squareup.anvil.compiler.api.AnvilContext
import com.squareup.anvil.compiler.api.CodeGenerator
import com.squareup.anvil.compiler.api.GeneratedFile
import com.squareup.anvil.compiler.api.createGeneratedFile
import com.squareup.anvil.compiler.codegen.PrivateCodeGenerator
import com.squareup.anvil.compiler.codegen.injectConstructor
import com.squareup.anvil.compiler.codegen.ksp.AnvilSymbolProcessor
import com.squareup.anvil.compiler.codegen.ksp.AnvilSymbolProcessorProvider
import com.squareup.anvil.compiler.codegen.ksp.isAnnotationPresent
import com.squareup.anvil.compiler.injectFqName
import com.squareup.anvil.compiler.internal.asClassName
import com.squareup.anvil.compiler.internal.createAnvilSpec
import com.squareup.anvil.compiler.internal.reference.asClassId
import com.squareup.anvil.compiler.internal.reference.asClassName
import com.squareup.anvil.compiler.internal.reference.classAndInnerClassReferences
import com.squareup.anvil.compiler.internal.reference.generateClassName
import com.squareup.anvil.compiler.internal.safePackageString
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier.OVERRIDE
import com.squareup.kotlinpoet.KModifier.PRIVATE
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.jvm.jvmStatic
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeVariableName
import com.squareup.kotlinpoet.ksp.writeTo
import dagger.internal.Factory
import java.io.File
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.psi.KtFile

internal object InjectConstructorFactoryGenerator : AnvilApplicabilityChecker {

  override fun isApplicable(context: AnvilContext) = context.generateFactories

  fun generateFactoryClass(
      clazz: ClassName,
      typeParameters: List<TypeVariableName>,
      constructorParameters: List<ConstructorParameter>,
      memberInjectParameters: List<MemberInjectParameter>,
  ): FileSpec {
    val classId = clazz.generateClassName(suffix = "_Factory").asClassId()
    val packageName = clazz.packageName.safePackageString()
    val className = classId.relativeClassName.asString()

    val allParameters = constructorParameters + memberInjectParameters
    val factoryClass = classId.asClassName()
    val factoryClassParameterized =
        if (typeParameters.isNotEmpty()) factoryClass.parameterizedBy(typeParameters)
        else factoryClass

    val classType =
        if (typeParameters.isNotEmpty()) clazz.parameterizedBy(typeParameters) else clazz

    return FileSpec.createAnvilSpec(packageName, className) {
      val canGenerateAnObject = allParameters.isEmpty() && typeParameters.isEmpty()
      val classBuilder =
          if (canGenerateAnObject) {
            TypeSpec.objectBuilder(factoryClass)
          } else {
            TypeSpec.classBuilder(factoryClass)
          }
      classBuilder.addTypeVariables(typeParameters)

      classBuilder
          .addSuperinterface(Factory::class.asClassName().parameterizedBy(classType))
          .apply {
            if (allParameters.isNotEmpty()) {
              primaryConstructor(
                  FunSpec.constructorBuilder()
                      .apply {
                        allParameters.forEach { parameter ->
                          addParameter(parameter.name, parameter.providerTypeName)
                        }
                      }
                      .build())
              allParameters.forEach { parameter ->
                addProperty(
                    PropertySpec.builder(parameter.name, parameter.providerTypeName)
                        .initializer(parameter.name)
                        .addModifiers(PRIVATE)
                        .build())
              }
            }
          }
          .addFunction(
              FunSpec.builder("get")
                  .addModifiers(OVERRIDE)
                  .returns(classType)
                  .apply {
                    val newInstanceArgumentList =
                        constructorParameters.asArgumentList(
                            asProvider = true, includeModule = false)
                    if (memberInjectParameters.isEmpty()) {
                      addStatement("return newInstance($newInstanceArgumentList)")
                    } else {
                      val instanceName = "instance"
                      addStatement("val $instanceName = newInstance($newInstanceArgumentList)")
                      addMemberInjection(memberInjectParameters, instanceName)
                      addStatement("return $instanceName")
                    }
                  }
                  .build())
          .apply {
            val builder = if (canGenerateAnObject) this else TypeSpec.companionObjectBuilder()
            builder
                .addFunction(
                    FunSpec.builder("create")
                        .jvmStatic()
                        .apply {
                          if (typeParameters.isNotEmpty()) {
                            addTypeVariables(typeParameters)
                          }
                          if (canGenerateAnObject) {
                            addStatement("return this")
                          } else {
                            allParameters.forEach { parameter ->
                              addParameter(parameter.name, parameter.providerTypeName)
                            }
                            val argumentList =
                                allParameters.asArgumentList(
                                    asProvider = false, includeModule = false)
                            addStatement("return %T($argumentList)", factoryClassParameterized)
                          }
                        }
                        .returns(factoryClassParameterized)
                        .build())
                .addFunction(
                    FunSpec.builder("newInstance")
                        .jvmStatic()
                        .apply {
                          if (typeParameters.isNotEmpty()) {
                            addTypeVariables(typeParameters)
                          }
                          constructorParameters.forEach { parameter ->
                            addParameter(name = parameter.name, type = parameter.originalTypeName)
                          }
                          val argumentsWithoutModule =
                              constructorParameters.joinToString { it.name }
                          addStatement("return %T($argumentsWithoutModule)", classType)
                        }
                        .returns(classType)
                        .build())
                .build()
                .let {
                  if (!canGenerateAnObject) {
                    addType(it)
                  }
                }
          }
          .build()
          .let { addType(it) }
    }
  } // generateFactoryClass

  internal class KspGenerator(
      override val env: SymbolProcessorEnvironment,
  ) : AnvilSymbolProcessor() {

    @AutoService(SymbolProcessorProvider::class)
    class Provider :
        AnvilSymbolProcessorProvider(InjectConstructorFactoryGenerator, ::KspGenerator)

    override fun processChecked(resolver: Resolver): List<KSAnnotated> {
      val tmp = resolver.getSymbolsWithAnnotation(injectFqName.toString()).toList()
        .mapNotNull { annotated ->
          when {
            annotated !is KSPropertyDeclaration -> {
              env.logger.error(
                "Filtering out PropertyDeclarations only", annotated
              )
              return@mapNotNull null
            }
            else -> annotated
          }
        }
      val files = resolver.getAllFiles()
      files.forEach { file ->
        env.logger.info("Checking file: ${file.filePath}")
        val declarations = file.declarations
        declarations.forEach { clazz -> //KSClassDeclarationImpl
          env.logger.info("Checking Class: ${clazz.qualifiedName?.asString()}")

          // Only generate a MembersInjector if the target class declares its own member-injected
          // properties. If it does, then any properties from superclasses must be added as well
          // (clazz.memberInjectParameters() will do this).
          //val hasInjectableProperties =
          //    clazz
          //        .getDeclaredProperties()
          //        .filter { it.getVisibility() != KspVisibility.PRIVATE }
          //        .any { it.isAnnotationPresent(injectFqName.asString()) }

          if (true) {

            val typeParameters = clazz.typeParameters.map { it.toTypeVariableName() }
            val constructorParameters = emptyList<ConstructorParameter>()
            val memberInjectParameters = emptyList<MemberInjectParameter>()
            val classId = (clazz as KSClassDeclaration)
              .toClassName()
              .generateClassName(separator = "_", suffix = "_Factory")
              .asClassId()
              //.generateClassName(separator, suffix).asClassId()
            val className = classId.relativeClassName.asString()

            val spec =
              generateFactoryClass(
                clazz = (clazz as KSClassDeclaration).toClassName(),
                typeParameters = typeParameters,
                constructorParameters = constructorParameters,
                memberInjectParameters = memberInjectParameters,
                )

            //createGeneratedFile(env.codeGenerator.)
            spec.writeTo(
              env.codeGenerator,
              aggregating = false,
              originatingKSFiles = listOf(file),
              )
          }
        }
      }
      return emptyList()
    }

    private fun createGeneratedFile(
      codeGenDir: File,
      packageName: String,
      fileName: String,
      content: String,
    ): GeneratedFile {
      val directory = File(codeGenDir, packageName.replace('.', File.separatorChar))
      val file = File(directory, "$fileName.kt")
      check(file.parentFile.exists() || file.parentFile.mkdirs()) {
        "Could not generate package directory: ${file.parentFile}"
      }
      file.writeText(content)

      return GeneratedFile(file, content)
    }
    private fun KSFile.forEachClassDeclaration(action: (KSClassDeclaration) -> Unit) {

      fun KSDeclarationContainer.getClassDeclarations(): Sequence<KSClassDeclaration> {
        return declarations.filterIsInstance<KSClassDeclaration>().flatMap { clazz ->
          sequence {
            yield(clazz)
            yieldAll(clazz.getClassDeclarations())
          }
        }
      }

      getClassDeclarations().forEach(action)
    }
  } // KSP

  @AutoService(CodeGenerator::class)
  internal class EmbeddedGenerator : PrivateCodeGenerator() {

    override fun isApplicable(context: AnvilContext) =
        InjectConstructorFactoryGenerator.isApplicable(context)

    override fun generateCodePrivate(
        codeGenDir: File,
        module: ModuleDescriptor,
        projectFiles: Collection<KtFile>
    ) {
      projectFiles.classAndInnerClassReferences(module).forEach { clazz ->
        clazz.constructors
            .injectConstructor()
            ?.takeIf { it.isAnnotatedWith(injectFqName) }
            ?.let {
              val classId = clazz.generateClassName(suffix = "_Factory")
              val packageName = classId.packageFqName.safePackageString()
              val className = classId.relativeClassName.asString()
              val typeParameters = clazz.typeParameters.map { it.typeVariableName }
              val constructorParameters = it.parameters.mapToConstructorParameters()

              val content =
                  generateFactoryClass(
                      clazz.asClassName(),
                      typeParameters,
                      constructorParameters,
                      clazz.memberInjectParameters())

              createGeneratedFile(codeGenDir, packageName, className, content.toString())
            }
      }
    }
  } // Embedded
}
