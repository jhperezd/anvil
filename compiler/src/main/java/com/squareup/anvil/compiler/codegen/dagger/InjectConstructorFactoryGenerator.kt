package com.squareup.anvil.compiler.codegen.dagger

import com.google.auto.service.AutoService
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.KSValueParameter
import com.squareup.anvil.annotations.ExperimentalAnvilApi
import com.squareup.anvil.compiler.api.AnvilApplicabilityChecker
import com.squareup.anvil.compiler.api.AnvilContext
import com.squareup.anvil.compiler.api.CodeGenerator
import com.squareup.anvil.compiler.api.GeneratedFile
import com.squareup.anvil.compiler.api.createGeneratedFile
import com.squareup.anvil.compiler.codegen.PrivateCodeGenerator
import com.squareup.anvil.compiler.codegen.injectConstructor
import com.squareup.anvil.compiler.codegen.ksp.AnvilSymbolProcessor
import com.squareup.anvil.compiler.codegen.ksp.AnvilSymbolProcessorProvider
import com.squareup.anvil.compiler.injectFqName
import com.squareup.anvil.compiler.internal.asClassName
import com.squareup.anvil.compiler.internal.buildFile
import com.squareup.anvil.compiler.internal.createAnvilSpec
import com.squareup.anvil.compiler.internal.reference.ClassReference
import com.squareup.anvil.compiler.internal.reference.MemberFunctionReference
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
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.jvm.jvmStatic
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeVariableName
import com.squareup.kotlinpoet.ksp.writeTo
import dagger.internal.Factory
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.psi.KtFile
import java.io.File

internal object InjectConstructorFactoryGenerator : AnvilApplicabilityChecker {
  override fun isApplicable(context: AnvilContext) = context.generateFactories

  fun gFC2(
    originClass: ClassName,
    typeParameters: List<TypeVariableName>,
    constructorParameters: List<ConstructorParameter>,
  ): FileSpec {
    val classId = originClass.generateClassName(suffix = "_Factory").asClassId()
    val packageName = originClass.packageName.safePackageString()
    val className = classId.relativeClassName.asString()

    // in progress, "empty" now coz of the test I am using to build this first
    //val constructorParameters: List<ConstructorParameter> = emptyList()
    val memberInjectParameters: List<MemberInjectParameter> = arrayListOf()
    //val typeParameters: List<TypeParameterReference.Psi> = emptyList()

    val classType = originClass //original was: clazz.asClassName().optionallyParameterizedBy(typeParameters)

    val allParameters = constructorParameters + memberInjectParameters
    val factoryClass = classId.asClassName()
    val factoryClassParameterized =
      if(typeParameters.isNotEmpty()) factoryClass.parameterizedBy(typeParameters) else factoryClass

    //val factoryClassParameterized = factoryClass.optionallyParameterizedBy(typeParameters)
    //val classType = clazz.asClassName().optionallyParameterizedBy(typeParameters)

    return FileSpec.createAnvilSpec(packageName, className) {
      val canGenerateAnObject = allParameters.isEmpty() && typeParameters.isEmpty()
      val classBuilder = if (canGenerateAnObject) {
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
                .build()
            )

            allParameters.forEach { parameter ->
              addProperty(
                PropertySpec.builder(parameter.name, parameter.providerTypeName)
                  .initializer(parameter.name)
                  .addModifiers(PRIVATE)
                  .build()
              )
            }
          }
        }
        .addFunction(
          FunSpec.builder("get")
            .addModifiers(OVERRIDE)
            .returns(classType)
            .apply {
              val newInstanceArgumentList = constructorParameters.asArgumentList(
                asProvider = true,
                includeModule = false
              )

              if (memberInjectParameters.isEmpty()) {
                addStatement("return newInstance($newInstanceArgumentList)")
              } else {
                val instanceName = "instance"
                addStatement("val $instanceName = newInstance($newInstanceArgumentList)")
                addMemberInjection(memberInjectParameters, instanceName)
                addStatement("return $instanceName")
              }
            }
            .build()
        )
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

                    val argumentList = allParameters.asArgumentList(
                      asProvider = false,
                      includeModule = false
                    )

                    addStatement(
                      "return %T($argumentList)",
                      factoryClassParameterized
                    )
                  }
                }
                .returns(factoryClassParameterized)
                .build()
            )
            .addFunction(
              FunSpec.builder("newInstance")
                .jvmStatic()
                .apply {
                  if (typeParameters.isNotEmpty()) {
                    addTypeVariables(typeParameters)
                  }
                  constructorParameters.forEach { parameter ->
                    addParameter(
                      name = parameter.name,
                      type = parameter.originalTypeName
                    )
                  }
                  val argumentsWithoutModule = constructorParameters.joinToString { it.name }

                  addStatement("return %T($argumentsWithoutModule)", classType)
                }
                .returns(classType)
                .build()
            )
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
  }

  internal class KspGenerator(
    override val env: SymbolProcessorEnvironment,
  ) : AnvilSymbolProcessor() {

    @AutoService(SymbolProcessorProvider::class)
    class Provider : AnvilSymbolProcessorProvider(InjectConstructorFactoryGenerator, ::KspGenerator)

    override fun processChecked(resolver: Resolver): List<KSAnnotated> {
      resolver.getSymbolsWithAnnotation(injectFqName.toString())
        .mapNotNull { annotated ->
          when {
            annotated !is KSFunctionDeclaration -> {
              env.logger.error(
                "Only methods can be annotated with @Inject.", annotated
              )
              return@mapNotNull null
            }
            else -> annotated
          }
        }
        .forEach { item -> //Item = KSFunctionDeclarationImpl, not KSClassDeclaration.
          //KSClassDeclarationImpl -> getClassDeclarationByName(name KSName)
          //ksClassDeclaration.parentDeclaration
          val clazz = item.parent as KSClassDeclaration
          val valueParameters = item.parameters //KSValueParameterImpl list
          //val properties = clazz.getAllProperties() //
          val typeParameters = clazz.typeParameters //KSTypeParameterImpl list

          // parameters -> 1 "factory" matching dagger true, ksp. Note this is an arrayList of KSValueParameterImpl, so we need to iterate on it.
          // properties -> 1 "factory", This is a TransformingSequence -> FilteringSequence -> ArrayList -> PropertyDescriptorImpl

          //Generate TypeParameters
          val tp = typeParameters.map { it.toTypeVariableName()
            /*kstTypeParameter ->
            val typeParameterName = kstTypeParameter.name.asString()
            val bounds = kstTypeParameter.bounds.map { kstTypeReference ->
              createTypeVariableNameFRomKSType(kstTypeReference)
            }
            TypeVariableName(typeParameterName, bounds.toList())
             */
          }

          //Generate Parameter (AKA ConstructorParameters)
          val cp = valueParameters.mapKSPToConstructorParameters()
          //constructor.parameters.mapToConstructorParameters()

          //Generate Property (AKA MemberInjectParameters)
          val mip = clazz.memberInjectParameters()

          val spec = gFC2(clazz.toClassName(), tp, cp)

          spec.writeTo(
            env.codeGenerator,
            aggregating = false,
            originatingKSFiles = listOf(clazz.containingFile!!)
          )
        }

      return emptyList()
    }
  }

  fun createTypeVariableNameFRomKSType(kstTypeReference: KSTypeReference): TypeVariableName {
    return TypeVariableName(kstTypeReference.resolve().declaration.simpleName.asString())
  }

  @AutoService(CodeGenerator::class)
  internal class EmbeddedGenerator : PrivateCodeGenerator() {

    override fun isApplicable(context: AnvilContext) =
      InjectConstructorFactoryGenerator.isApplicable(context)
    override fun generateCodePrivate(
      codeGenDir: File,
      module: ModuleDescriptor,
      projectFiles: Collection<KtFile>
    ) {
      projectFiles
        .classAndInnerClassReferences(module)
        .forEach { clazz ->
          clazz.constructors
            .injectConstructor()
            ?.takeIf { it.isAnnotatedWith(injectFqName) }
            ?.let {
              //generateFactoryClass(codeGenDir, clazz, it)

              val content = gFC(clazz, it)
              val classId = clazz.generateClassName(suffix = "_Factory")
              val packageName = classId.packageFqName.safePackageString()
              val className = classId.relativeClassName.asString()
              //createGeneratedFile(codeGenDir, packageName, className, content)

              //***val content2 = gFC2(clazz.asClassName())
              //***createGeneratedFile(codeGenDir, packageName, className, content2.toString())

            }
        }
    }

    private fun gFC(
      clazz: ClassReference.Psi,
      constructor: MemberFunctionReference.Psi
    ): String {
      val classId = clazz.generateClassName(suffix = "_Factory")

      val packageName = classId.packageFqName.safePackageString()
      val className = classId.relativeClassName.asString()

      val constructorParameters = constructor.parameters.mapToConstructorParameters() // ArrayList of ConstructorParameter
      val memberInjectParameters = clazz.memberInjectParameters()

      val allParameters = constructorParameters + memberInjectParameters

      val typeParameters = clazz.typeParameters

      val factoryClass = classId.asClassName()
      val factoryClassParameterized = factoryClass.optionallyParameterizedBy(typeParameters)
      val classType = clazz.asClassName().optionallyParameterizedBy(typeParameters)

      val content = FileSpec.buildFile(packageName, className) {
        val canGenerateAnObject = allParameters.isEmpty() && typeParameters.isEmpty()
        val classBuilder = if (canGenerateAnObject) {
          TypeSpec.objectBuilder(factoryClass)
        } else {
          TypeSpec.classBuilder(factoryClass)
        }
        typeParameters.forEach { classBuilder.addTypeVariable(it.typeVariableName) }

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
                  .build()
              )

              allParameters.forEach { parameter ->
                addProperty(
                  PropertySpec.builder(parameter.name, parameter.providerTypeName)
                    .initializer(parameter.name)
                    .addModifiers(PRIVATE)
                    .build()
                )
              }
            }
          }
          .addFunction(
            FunSpec.builder("get")
              .addModifiers(OVERRIDE)
              .returns(classType)
              .apply {
                val newInstanceArgumentList = constructorParameters.asArgumentList(
                  asProvider = true,
                  includeModule = false
                )

                if (memberInjectParameters.isEmpty()) {
                  addStatement("return newInstance($newInstanceArgumentList)")
                } else {
                  val instanceName = "instance"
                  addStatement("val $instanceName = newInstance($newInstanceArgumentList)")
                  addMemberInjection(memberInjectParameters, instanceName)
                  addStatement("return $instanceName")
                }
              }
              .build()
          )
          .apply {
            val builder = if (canGenerateAnObject) this else TypeSpec.companionObjectBuilder()
            builder
              .addFunction(
                FunSpec.builder("create")
                  .jvmStatic()
                  .apply {
                    if (typeParameters.isNotEmpty()) {
                      addTypeVariables(typeParameters.map { it.typeVariableName })
                    }
                    if (canGenerateAnObject) {
                      addStatement("return this")
                    } else {
                      allParameters.forEach { parameter ->
                        addParameter(parameter.name, parameter.providerTypeName)
                      }

                      val argumentList = allParameters.asArgumentList(
                        asProvider = false,
                        includeModule = false
                      )

                      addStatement(
                        "return %T($argumentList)",
                        factoryClassParameterized
                      )
                    }
                  }
                  .returns(factoryClassParameterized)
                  .build()
              )
              .addFunction(
                FunSpec.builder("newInstance")
                  .jvmStatic()
                  .apply {
                    if (typeParameters.isNotEmpty()) {
                      addTypeVariables(typeParameters.map { it.typeVariableName })
                    }
                    constructorParameters.forEach { parameter ->
                      addParameter(
                        name = parameter.name,
                        type = parameter.originalTypeName
                      )
                    }
                    val argumentsWithoutModule = constructorParameters.joinToString { it.name }

                    addStatement("return %T($argumentsWithoutModule)", classType)
                  }
                  .returns(classType)
                  .build()
              )
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

      return content
    }

    private fun generateFactoryClass(
      codeGenDir: File,
      clazz: ClassReference.Psi,
      constructor: MemberFunctionReference.Psi
    ): GeneratedFile {
      val classId = clazz.generateClassName(suffix = "_Factory")

      val packageName = classId.packageFqName.safePackageString()
      val className = classId.relativeClassName.asString()

      val constructorParameters = constructor.parameters.mapToConstructorParameters()
      val memberInjectParameters = clazz.memberInjectParameters()

      val allParameters = constructorParameters + memberInjectParameters

      val typeParameters = clazz.typeParameters

      val factoryClass = classId.asClassName()
      val factoryClassParameterized = factoryClass.optionallyParameterizedBy(typeParameters)
      val classType = clazz.asClassName().optionallyParameterizedBy(typeParameters)

      val content = FileSpec.buildFile(packageName, className) {
        val canGenerateAnObject = allParameters.isEmpty() && typeParameters.isEmpty()
        val classBuilder = if (canGenerateAnObject) {
          TypeSpec.objectBuilder(factoryClass)
        } else {
          TypeSpec.classBuilder(factoryClass)
        }
        typeParameters.forEach { classBuilder.addTypeVariable(it.typeVariableName) }

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
                  .build()
              )

              allParameters.forEach { parameter ->
                addProperty(
                  PropertySpec.builder(parameter.name, parameter.providerTypeName)
                    .initializer(parameter.name)
                    .addModifiers(PRIVATE)
                    .build()
                )
              }
            }
          }
          .addFunction(
            FunSpec.builder("get")
              .addModifiers(OVERRIDE)
              .returns(classType)
              .apply {
                val newInstanceArgumentList = constructorParameters.asArgumentList(
                  asProvider = true,
                  includeModule = false
                )

                if (memberInjectParameters.isEmpty()) {
                  addStatement("return newInstance($newInstanceArgumentList)")
                } else {
                  val instanceName = "instance"
                  addStatement("val $instanceName = newInstance($newInstanceArgumentList)")
                  addMemberInjection(memberInjectParameters, instanceName)
                  addStatement("return $instanceName")
                }
              }
              .build()
          )
          .apply {
            val builder = if (canGenerateAnObject) this else TypeSpec.companionObjectBuilder()
            builder
              .addFunction(
                FunSpec.builder("create")
                  .jvmStatic()
                  .apply {
                    if (typeParameters.isNotEmpty()) {
                      addTypeVariables(typeParameters.map { it.typeVariableName })
                    }
                    if (canGenerateAnObject) {
                      addStatement("return this")
                    } else {
                      allParameters.forEach { parameter ->
                        addParameter(parameter.name, parameter.providerTypeName)
                      }

                      val argumentList = allParameters.asArgumentList(
                        asProvider = false,
                        includeModule = false
                      )

                      addStatement(
                        "return %T($argumentList)",
                        factoryClassParameterized
                      )
                    }
                  }
                  .returns(factoryClassParameterized)
                  .build()
              )
              .addFunction(
                FunSpec.builder("newInstance")
                  .jvmStatic()
                  .apply {
                    if (typeParameters.isNotEmpty()) {
                      addTypeVariables(typeParameters.map { it.typeVariableName })
                    }
                    constructorParameters.forEach { parameter ->
                      addParameter(
                        name = parameter.name,
                        type = parameter.originalTypeName
                      )
                    }
                    val argumentsWithoutModule = constructorParameters.joinToString { it.name }

                    addStatement("return %T($argumentsWithoutModule)", classType)
                  }
                  .returns(classType)
                  .build()
              )
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

      return createGeneratedFile(codeGenDir, packageName, className, content)
    }
  }
}
