package com.squareup.anvil.compiler.dagger

import com.google.common.truth.Truth.assertThat
import com.squareup.anvil.compiler.injectClass
import com.squareup.anvil.compiler.internal.testing.AnvilCompilationMode
import com.squareup.anvil.compiler.internal.testing.compileAnvil
import com.squareup.anvil.compiler.internal.testing.createInstance
import com.squareup.anvil.compiler.internal.testing.factoryClass
import com.squareup.anvil.compiler.internal.testing.getPropertyValue
import com.squareup.anvil.compiler.internal.testing.isStatic
import com.squareup.anvil.compiler.isError
import com.squareup.anvil.compiler.isFullTestRun
import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation.ExitCode.OK
import dagger.Lazy
import dagger.internal.Factory
import org.intellij.lang.annotations.Language
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters
import java.io.File
import javax.inject.Provider

@RunWith(value = Parameterized::class)
class InjectConstructorFactoryGeneratorTestNew(
  private val useDagger: Boolean,
  private val mode: AnvilCompilationMode
) {

  companion object {

    @Parameters(name = "Dagger: {0}, Mode: {1}")
    @JvmStatic fun data() : List<Array<Any>> {
      return listOf(
        arrayOf(isFullTestRun(), AnvilCompilationMode.Embedded()),
        arrayOf(false, AnvilCompilationMode.Embedded()),
        arrayOf(isFullTestRun(), AnvilCompilationMode.Ksp()),
        arrayOf(false, AnvilCompilationMode.Ksp()),
      )
    }
  }

  @Test fun `a factory class is generated for an inject constructor without arguments`() {
    /*
package com.squareup.test;

import dagger.internal.Factory;
import javax.annotation.Generated;

@Generated(
    value = "dagger.internal.codegen.ComponentProcessor",
    comments = "https://dagger.dev"
)
@SuppressWarnings({
    "unchecked",
    "rawtypes"
})
public final class InjectClass_Factory implements Factory<InjectClass> {
  @Override
  public InjectClass get() {
    return newInstance();
  }

  public static InjectClass_Factory create() {
    return InstanceHolder.INSTANCE;
  }

  public static InjectClass newInstance() {
    return new InjectClass();
  }

  private static final class InstanceHolder {
    private static final InjectClass_Factory INSTANCE = new InjectClass_Factory();
  }
}
     */

    compile(
      """
      package com.squareup.test
      
      import javax.inject.Inject
      
      class InjectClass @Inject constructor()
      """
    ) {
      val factoryClass = injectClass.factoryClass()

      val constructor = factoryClass.declaredConstructors.single()
      assertThat(constructor.parameterTypes.toList()).isEmpty()

      val staticMethods = factoryClass.declaredMethods.filter { it.isStatic }

      val factoryInstance = staticMethods.single { it.name == "create" }
        .invoke(null)
      assertThat(factoryInstance::class.java).isEqualTo(factoryClass)

      val instance = staticMethods.single { it.name == "newInstance" }
        .invoke(null)

      assertThat(instance).isNotNull()
      assertThat((factoryInstance as Factory<*>).get()).isNotNull()
    }
  }

  /**
   * Covers a bug that previously led to conflicting imports in the generated code:
   * https://github.com/square/anvil/issues/738
   */
  @Test fun `a factory class is generated without conflicting imports`() {
    compile(
      """
      package com.squareup.test
      
      import javax.inject.Inject
      
      class InjectClass @Inject constructor() {
        interface Factory {
          fun doSomething()
        }
      }

      class InjectClassFactory @Inject constructor(val factory: InjectClass.Factory)
      """
    ) {
      // Loading one of the classes is all that's necessary to verify no conflicting imports were
      // generated
      injectClass.factoryClass()
    }
  }

  @Test fun `a factory class is generated for an inject constructor with arguments`() {
    /*
package com.squareup.test;

import dagger.internal.Factory;
import javax.annotation.Generated;
import javax.inject.Provider;

@Generated(
    value = "dagger.internal.codegen.ComponentProcessor",
    comments = "https://dagger.dev"
)
@SuppressWarnings({
    "unchecked",
    "rawtypes"
})
public final class InjectClass_Factory implements Factory<InjectClass> {
  private final Provider<String> stringProvider;

  private final Provider<Integer> p1_52215Provider;

  public InjectClass_Factory(Provider<String> stringProvider, Provider<Integer> p1_52215Provider) {
    this.stringProvider = stringProvider;
    this.p1_52215Provider = p1_52215Provider;
  }

  @Override
  public InjectClass get() {
    return newInstance(stringProvider.get(), p1_52215Provider.get());
  }

  public static InjectClass_Factory create(Provider<String> stringProvider,
      Provider<Integer> p1_52215Provider) {
    return new InjectClass_Factory(stringProvider, p1_52215Provider);
  }

  public static InjectClass newInstance(String string, int p1_52215) {
    return new InjectClass(string, p1_52215);
  }
}
     */

    compile(
      """
      package com.squareup.test
      
      import javax.inject.Inject
      
      data class InjectClass @Inject constructor(
        val string: String, 
        val int: Int
      )
      """
    ) {
      val factoryClass = injectClass.factoryClass()

      val constructor = factoryClass.declaredConstructors.single()
      assertThat(constructor.parameterTypes.toList())
        .containsExactly(Provider::class.java, Provider::class.java)
        .inOrder()

      val staticMethods = factoryClass.declaredMethods.filter { it.isStatic }

      val factoryInstance = staticMethods.single { it.name == "create" }
        .invoke(null, Provider { "abc" }, Provider { 1 })
      assertThat(factoryInstance::class.java).isEqualTo(factoryClass)

      val newInstance = staticMethods.single { it.name == "newInstance" }
        .invoke(null, "abc", 1)
      val getInstance = (factoryInstance as Factory<*>).get()

      assertThat(newInstance).isNotNull()
      assertThat(getInstance).isNotNull()

      assertThat(newInstance).isEqualTo(getInstance)
      assertThat(newInstance).isNotSameInstanceAs(getInstance)
    }
  }

  @Test
  fun `a factory class is generated for an inject constructor with Provider and Lazy arguments`() {
    /*
package com.squareup.test;

import dagger.Lazy;
import dagger.internal.DoubleCheck;
import dagger.internal.Factory;
import java.util.List;
import javax.annotation.Generated;
import javax.inject.Provider;

@Generated(
    value = "dagger.internal.codegen.ComponentProcessor",
    comments = "https://dagger.dev"
)
@SuppressWarnings({
    "unchecked",
    "rawtypes"
})
public final class InjectClass_Factory implements Factory<InjectClass> {
  private final Provider<String> stringProvider;

  private final Provider<String> stringProvider2;

  private final Provider<List<String>> stringListProvider;

  private final Provider<String> stringProvider3;

  public InjectClass_Factory(Provider<String> stringProvider, Provider<String> stringProvider2,
      Provider<List<String>> stringListProvider, Provider<String> stringProvider3) {
    this.stringProvider = stringProvider;
    this.stringProvider2 = stringProvider2;
    this.stringListProvider = stringListProvider;
    this.stringProvider3 = stringProvider3;
  }

  @Override
  public InjectClass get() {
    return newInstance(stringProvider.get(), stringProvider2, stringListProvider, DoubleCheck.lazy(stringProvider3));
  }

  public static InjectClass_Factory create(Provider<String> stringProvider,
      Provider<String> stringProvider2, Provider<List<String>> stringListProvider,
      Provider<String> stringProvider3) {
    return new InjectClass_Factory(stringProvider, stringProvider2, stringListProvider, stringProvider3);
  }

  public static InjectClass newInstance(String string, Provider<String> stringProvider,
      Provider<List<String>> stringListProvider, Lazy<String> lazyString) {
    return new InjectClass(string, stringProvider, stringListProvider, lazyString);
  }
}
     */

    @Suppress("EqualsOrHashCode")
    compile(
      """
      package com.squareup.test
      
      import dagger.Lazy
      import javax.inject.Inject
      import javax.inject.Provider
      
      class InjectClass @Inject constructor(
        val string: String, 
        val stringProvider: Provider<String>,
        val stringListProvider: Provider<List<String>>,
        val lazyString: Lazy<String>
      ) {
        override fun equals(other: Any?): Boolean {
          return toString() == other.toString()
        }
        override fun toString(): String {
         return string + stringProvider.get() + 
             stringListProvider.get()[0] + lazyString.get()
        }
      }
      """
    ) {
      val factoryClass = injectClass.factoryClass()

      val constructor = factoryClass.declaredConstructors.single()
      assertThat(constructor.parameterTypes.toList())
        .containsExactly(
          Provider::class.java,
          Provider::class.java,
          Provider::class.java,
          Provider::class.java
        )
        .inOrder()

      val staticMethods = factoryClass.declaredMethods.filter { it.isStatic }

      val factoryInstance = staticMethods.single { it.name == "create" }
        .invoke(
          null,
          Provider { "a" },
          Provider { "b" },
          Provider { listOf("c") },
          Provider { "d" }
        )
      assertThat(factoryInstance::class.java).isEqualTo(factoryClass)

      val newInstance = staticMethods.single { it.name == "newInstance" }
        .invoke(null, "a", Provider { "b" }, Provider { listOf("c") }, Lazy { "d" })
      val getInstance = (factoryInstance as Factory<*>).get()

      assertThat(newInstance).isNotNull()
      assertThat(getInstance).isNotNull()

      assertThat(newInstance).isEqualTo(getInstance)
      assertThat(newInstance).isNotSameInstanceAs(getInstance)
    }
  }

  private fun compile(
    @Language("kotlin") vararg sources: String,
    previousCompilationResult: JvmCompilationResult? = null,
    block: JvmCompilationResult.() -> Unit = { }
  ): JvmCompilationResult = compileAnvil(
    sources = sources,
    enableDaggerAnnotationProcessor = useDagger,
    generateDaggerFactories = !useDagger,
    // Many constructor parameters are unused.
    allWarningsAsErrors = false,
    previousCompilationResult = previousCompilationResult,
    block = block,
    mode = mode
  )
}
