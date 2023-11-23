package com.squareup.anvil.compiler.dagger

import com.squareup.anvil.compiler.internal.testing.AnvilCompilationMode

sealed interface AnvilDaggerTestCase {
  data object UseDagger : AnvilDaggerTestCase
  data class UseAnvil(
    val mode: AnvilCompilationMode
  ) : AnvilDaggerTestCase
}
