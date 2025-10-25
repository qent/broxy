package io.qent.broxy.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.rules.KotlinCoreEnvironmentTest
import io.gitlab.arturbosch.detekt.test.TestConfig
import io.gitlab.arturbosch.detekt.test.compileAndLintWithContext
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals

@KotlinCoreEnvironmentTest
class NoFullyQualifiedNamesTest(
    private val env: KotlinCoreEnvironment,
) {
    @Test
    fun `reports fully qualified call`() {
        val code =
            """
            fun test() {
                kotlin.io.println("ok")
            }
            """.trimIndent()

        val findings = NoFullyQualifiedNames(Config.empty).compileAndLintWithContext(env, code)

        assertEquals(1, findings.size)
    }

    @Test
    fun `reports fully qualified type`() {
        val code =
            """
            fun test(): kotlin.collections.List<String> = emptyList()
            """.trimIndent()

        val findings = NoFullyQualifiedNames(Config.empty).compileAndLintWithContext(env, code)

        assertEquals(1, findings.size)
    }

    @Test
    fun `allows imported references`() {
        val code =
            """
            import kotlin.io.println

            fun test() {
                println("ok")
            }
            """.trimIndent()

        val findings = NoFullyQualifiedNames(Config.empty).compileAndLintWithContext(env, code)

        assertEquals(0, findings.size)
    }

    @Test
    fun `honors allowed prefixes`() {
        val code =
            """
            fun test() {
                kotlin.io.println("ok")
            }
            """.trimIndent()

        val config = TestConfig("allowedPrefixes" to listOf("kotlin.io"))

        val findings = NoFullyQualifiedNames(config).compileAndLintWithContext(env, code)

        assertEquals(0, findings.size)
    }

    @Test
    fun `does not report object qualifiers`() {
        val code =
            """
            object util {
                fun ping() = Unit
            }

            fun test() {
                util.ping()
            }
            """.trimIndent()

        val findings = NoFullyQualifiedNames(Config.empty).compileAndLintWithContext(env, code)

        assertEquals(0, findings.size)
    }
}
