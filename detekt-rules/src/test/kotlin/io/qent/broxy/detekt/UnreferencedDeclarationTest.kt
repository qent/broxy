package io.qent.broxy.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.test.TestConfig
import io.gitlab.arturbosch.detekt.test.compileAndLint
import kotlin.test.Test
import kotlin.test.assertEquals

class UnreferencedDeclarationTest {
    @Test
    fun `reports unreferenced public member function`() {
        val code =
            """
            package sample

            class AppStore {
                fun listServerConfigs(): List<String> = emptyList()
            }
            """.trimIndent()

        val config = TestConfig("useRepositoryIndex" to false)

        val findings = UnreferencedDeclaration(config).compileAndLint(code)

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

        val config = TestConfig("useRepositoryIndex" to false)

        val findings = UnreferencedDeclaration(config).compileAndLint(code)

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
                "allowlistFqNames" to listOf("sample.AppStore.listServerConfigs"),
            )

        val findings = UnreferencedDeclaration(config).compileAndLint(code)

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

        val findings = UnreferencedDeclaration(Config.empty).compileAndLint(code)

        assertEquals(0, findings.size)
    }
}
