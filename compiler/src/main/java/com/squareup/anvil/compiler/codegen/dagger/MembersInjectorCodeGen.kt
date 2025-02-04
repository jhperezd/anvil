package com.squareup.anvil.compiler.codegen.dagger

import com.google.auto.service.AutoService
import com.google.devtools.ksp.isPrivate
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.squareup.anvil.compiler.api.AnvilApplicabilityChecker
import com.squareup.anvil.compiler.api.AnvilContext
import com.squareup.anvil.compiler.api.CodeGenerator
import com.squareup.anvil.compiler.api.GeneratedFile
import com.squareup.anvil.compiler.api.createGeneratedFile
import com.squareup.anvil.compiler.codegen.PrivateCodeGenerator
import com.squareup.anvil.compiler.codegen.ksp.AnvilSymbolProcessor
import com.squareup.anvil.compiler.codegen.ksp.AnvilSymbolProcessorProvider
import com.squareup.anvil.compiler.injectFqName
import com.squareup.anvil.compiler.internal.capitalize
import com.squareup.anvil.compiler.internal.createAnvilSpec
import com.squareup.anvil.compiler.internal.reference.ClassReference
import com.squareup.anvil.compiler.internal.reference.Visibility
import com.squareup.anvil.compiler.internal.reference.asClassName
import com.squareup.anvil.compiler.internal.reference.classAndInnerClassReferences
import com.squareup.anvil.compiler.internal.reference.generateClassName
import com.squareup.anvil.compiler.internal.safePackageString
import com.squareup.kotlinpoet.AnnotationSpec
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
import dagger.MembersInjector
import dagger.internal.InjectedFieldSignature
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.psi.KtFile
import java.io.File

internal object MembersInjectorCodeGen : AnvilApplicabilityChecker {
  override fun isApplicable(context: AnvilContext) = context.generateFactories

  internal class KspGenerator(
    override val env: SymbolProcessorEnvironment,
  ) : AnvilSymbolProcessor() {
    @AutoService(SymbolProcessorProvider::class)
    class Provider : AnvilSymbolProcessorProvider(MembersInjectorCodeGen, ::KspGenerator)

    override fun processChecked(resolver: Resolver): List<KSAnnotated> {
      resolver.getSymbolsWithAnnotation(injectFqName.asString())
        .filterIsInstance<KSPropertyDeclaration>()
        .filterNot { it.isPrivate() }
        .filter { it.parentDeclaration is KSClassDeclaration }
        .groupBy { it.parentDeclaration as KSClassDeclaration }
        .forEach { (clazz, _) ->
          val typeParameters = clazz.typeParameters
            .map { it.toTypeVariableName() }
          val isGeneric = typeParameters.isNotEmpty()

          generateMembersInjectorClass(
            origin = clazz.toClassName(),
            isGeneric = isGeneric,
            typeParameters = typeParameters,
            parameters = clazz.memberInjectParameters(),
          )
            .writeTo(env.codeGenerator, aggregating = false, listOf(clazz.containingFile!!))
        }

      return emptyList()
    }
  }

  @AutoService(CodeGenerator::class)
  internal class Embedded : PrivateCodeGenerator() {

    override fun isApplicable(context: AnvilContext) = MembersInjectorCodeGen.isApplicable(context)

    override fun generateCodePrivate(
      codeGenDir: File,
      module: ModuleDescriptor,
      projectFiles: Collection<KtFile>,
    ) {
      projectFiles
        .classAndInnerClassReferences(module)
        .filterNot { it.isInterface() }
        .forEach { clazz ->
          // Only generate a MembersInjector if the target class declares its own member-injected
          // properties. If it does, then any properties from superclasses must be added as well
          // (clazz.memberInjectParameters() will do this).
          clazz.properties
            .filter { it.visibility() != Visibility.PRIVATE }
            .filter { it.isAnnotatedWith(injectFqName) }
            .ifEmpty { return@forEach }

          generateMembersInjectorClass(
            codeGenDir = codeGenDir,
            clazz = clazz,
            parameters = clazz.memberInjectParameters(),
          )
        }
    }

    private fun generateMembersInjectorClass(
      codeGenDir: File,
      clazz: ClassReference.Psi,
      parameters: List<MemberInjectParameter>,
    ): GeneratedFile {
      val isGeneric = clazz.isGenericClass()
      val typeParameters = clazz.typeParameters
        .map { it.typeVariableName }

      val spec = generateMembersInjectorClass(
        origin = clazz.asClassName(),
        isGeneric = isGeneric,
        typeParameters = typeParameters,
        parameters = parameters,
      )

      return createGeneratedFile(codeGenDir, spec.packageName, spec.name, spec.toString())
    }
  }

  private fun generateMembersInjectorClass(
    origin: ClassName,
    isGeneric: Boolean,
    typeParameters: List<TypeVariableName>,
    parameters: List<MemberInjectParameter>,
  ): FileSpec {
    val memberInjectorClass = origin.generateClassName(suffix = "_MembersInjector")
    val packageName = memberInjectorClass.packageName.safePackageString()
    val fileName = memberInjectorClass.simpleName

    val classType = origin
      .let {
        if (isGeneric) {
          it.parameterizedBy(typeParameters)
        } else {
          it
        }
      }

    val membersInjectorType = MembersInjector::class.asClassName().parameterizedBy(classType)

    fun createArgumentList(asProvider: Boolean): String {
      return parameters
        .map { it.name }
        .let { list ->
          if (asProvider) list.map { "$it.get()" } else list
        }
        .joinToString()
    }

    val spec = FileSpec.createAnvilSpec(packageName, fileName) {
      addType(
        TypeSpec
          .classBuilder(memberInjectorClass)
          .addSuperinterface(membersInjectorType)
          .apply {
            typeParameters.forEach { typeParameter ->
              addTypeVariable(typeParameter)
            }
            primaryConstructor(
              FunSpec.constructorBuilder()
                .apply {
                  parameters.forEach { parameter ->
                    addParameter(parameter.name, parameter.resolvedProviderTypeName)
                  }
                }
                .build(),
            )

            parameters.forEach { parameter ->
              addProperty(
                PropertySpec.builder(parameter.name, parameter.resolvedProviderTypeName)
                  .initializer(parameter.name)
                  .addModifiers(PRIVATE)
                  .build(),
              )
            }
          }
          .addFunction(
            FunSpec.builder("injectMembers")
              .addModifiers(OVERRIDE)
              .addParameter("instance", classType)
              .addMemberInjection(parameters, "instance")
              .build(),
          )
          .addType(
            TypeSpec
              .companionObjectBuilder()
              .addFunction(
                FunSpec.builder("create")
                  .jvmStatic()
                  .apply {
                    typeParameters.forEach { typeParameter ->
                      addTypeVariable(typeParameter)
                    }

                    parameters.forEach { parameter ->
                      addParameter(parameter.name, parameter.resolvedProviderTypeName)
                    }

                    addStatement(
                      "return %T(${createArgumentList(false)})",
                      memberInjectorClass,
                    )
                  }
                  .returns(membersInjectorType)
                  .build(),
              )
              .apply {
                parameters
                  // Don't generate the static single-property "inject___" functions for super-classes
                  .filter { it.memberInjectorClassName == memberInjectorClass }
                  .forEach { parameter ->

                    val name = parameter.name

                    addFunction(
                      FunSpec.builder("inject${parameter.accessName.capitalize()}")
                        .jvmStatic()
                        .apply {
                          typeParameters.forEach { typeParameter ->
                            addTypeVariable(typeParameter)
                          }

                          // Don't add @InjectedFieldSignature when it's calling a setter method
                          if (!parameter.isSetterInjected) {
                            addAnnotation(
                              AnnotationSpec.builder(InjectedFieldSignature::class)
                                .addMember("%S", parameter.injectedFieldSignature)
                                .build(),
                            )
                          }
                        }
                        .addAnnotations(parameter.qualifierAnnotationSpecs)
                        .addParameter("instance", classType)
                        .addParameter(name, parameter.originalTypeName)
                        .addStatement("instance.${parameter.originalName} = $name")
                        .build(),
                    )
                  }
              }
              .build(),
          )
          .build(),
      )
    }

    return spec
  }
}
