package com.glassstorm.phonemanager.testing.architecture

import com.google.common.truth.Truth.assertThat
import com.lemonappdev.konsist.api.Konsist
import org.junit.Test

/**
 * ANTI-VACUITY GATE. A Konsist suite that scans zero files satisfies every rule
 * vacuously, which is worse than having no rules at all. This test fails loudly
 * if the scope is empty or if any module the laws depend on is invisible, and it
 * prints the per-module file counts so a reviewer can see what was parsed.
 */
class ArchitectureScopeTest {
    @Test
    fun `every module's authored sources are visible to the rules`() {
        val counts =
            ArchitectureScope.SCOPE_PATHS.associateWith { path ->
                Konsist.scopeFromDirectory(path).files.size
            }

        counts.forEach { (path, count) -> println("konsist-scope: $path -> $count files") }

        assertThat(counts.values).doesNotContain(0)
        // 149 authored `.kt` files across the nine pre-existing modules, plus the
        // architecture module's own sources. A drop to zero here would make every
        // boundary law below pass without checking anything.
        assertThat(counts.values.sum()).isAtLeast(149)
    }
}
