package io.qent.broxy.detekt

import io.gitlab.arturbosch.detekt.rules.KotlinCoreEnvironmentTest
import io.gitlab.arturbosch.detekt.test.TestConfig
import io.gitlab.arturbosch.detekt.test.compileAndLintWithContext
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@KotlinCoreEnvironmentTest
class UnreferencedDeclarationTest(
    private val env: KotlinCoreEnvironment,
) {
    @Test
    fun `reports unreferenced public member function`() {
        val code =
            """
            package sample

            class AppStore {
                fun listServerConfigs(): List<String> = emptyList()
            }
            """.trimIndent()

        val config = TestConfig("useRepositoryIndex" to false, "includeTestSources" to true)

        val findings = UnreferencedDeclaration(config).compileAndLintWithContext(env, code)

        assertEquals(1, findings.size)
    }

    @Test
    fun `does not report when declaration is referenced`() {
        val code =
            """
            package sample

            class AppStore {
                fun listServerConfigs(): List<String> = emptyList()
            }

            fun main() {
                AppStore().listServerConfigs()
            }
            """.trimIndent()

        val config = TestConfig("useRepositoryIndex" to false, "includeTestSources" to true)

        val findings = UnreferencedDeclaration(config).compileAndLintWithContext(env, code)

        assertEquals(0, findings.size)
    }

    @Test
    fun `honors FQ name allowlist`() {
        val code =
            """
            package sample

            class AppStore {
                fun listServerConfigs(): List<String> = emptyList()
            }
            """.trimIndent()

        val config =
            TestConfig(
                "useRepositoryIndex" to false,
                "includeTestSources" to true,
                "allowlistFqNames" to listOf("sample.AppStore.listServerConfigs"),
            )

        val findings = UnreferencedDeclaration(config).compileAndLintWithContext(env, code)

        assertEquals(0, findings.size)
    }

    @Test
    fun `ignores private declarations`() {
        val code =
            """
            package sample

            class AppStore {
                private fun listServerConfigs(): List<String> = emptyList()
            }
            """.trimIndent()

        val config = TestConfig("useRepositoryIndex" to false, "includeTestSources" to true)
        val findings = UnreferencedDeclaration(config).compileAndLintWithContext(env, code)

        assertEquals(0, findings.size)
    }

    @Test
    fun `reports companion function when same simple name is used in different type`() {
        val code =
            """
            package sample

            class Preset {
                companion object {
                    fun allEnabled() = "preset"
                    fun presetManagement() = "preset"
                }
            }

            class UiPresetCore {
                companion object {
                    fun allEnabled() = "ui"
                    fun presetManagement() = "ui"
                }
            }

            fun main() {
                Preset.allEnabled()
                Preset.presetManagement()
            }
            """.trimIndent()

        val config = TestConfig("useRepositoryIndex" to false, "includeTestSources" to true)
        val findings = UnreferencedDeclaration(config).compileAndLintWithContext(env, code)
        val messages = findings.map { it.message }

        assertEquals(2, findings.size)
        assertTrue(messages.any { it.contains("sample.UiPresetCore.Companion.allEnabled") })
        assertTrue(messages.any { it.contains("sample.UiPresetCore.Companion.presetManagement") })
    }

    @Test
    fun `does not report callable reference usage`() {
        val code =
            """
            package sample

            class AppStore {
                fun listServerConfigs(): List<String> = emptyList()
            }

            fun main() {
                val f = AppStore::listServerConfigs
                f(AppStore())
            }
            """.trimIndent()

        val config = TestConfig("useRepositoryIndex" to false, "includeTestSources" to true)
        val findings = UnreferencedDeclaration(config).compileAndLintWithContext(env, code)

        assertEquals(0, findings.size)
    }
}
