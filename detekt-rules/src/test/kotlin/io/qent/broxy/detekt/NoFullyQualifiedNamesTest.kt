package io.qent.broxy.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.rules.KotlinCoreEnvironmentTest
import io.gitlab.arturbosch.detekt.test.TestConfig
import io.gitlab.arturbosch.detekt.test.compileAndLint
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
    fun `reports fully qualified call without type resolution`() {
        val code =
            """
            fun test() {
                kotlin.io.println("ok")
            }
            """.trimIndent()

        val findings = NoFullyQualifiedNames(Config.empty).compileAndLint(code)

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
    fun `does not report object qualifiers without type resolution`() {
        val code =
            """
            object util {
                fun ping() = Unit
            }

            fun test() {
                util.ping()
            }
            """.trimIndent()

        val findings = NoFullyQualifiedNames(Config.empty).compileAndLint(code)

        assertEquals(0, findings.size)
    }

    @Test
    fun `does not report regular property access chains`() {
        val code =
            """
            data class Tool(val name: String)
            data class Caps(val tools: List<Tool>)

            fun names(caps: Caps): Set<String> {
                return caps.tools.map { it.name }.toSet()
            }
            """.trimIndent()

        val findings = NoFullyQualifiedNames(Config.empty).compileAndLint(code)

        assertEquals(0, findings.size)
    }

    @Test
    fun `reports known package root top level call without type resolution`() {
        val code =
            """
            suspend fun waitForIt() {
                kotlinx.coroutines.delay(1)
            }
            """.trimIndent()

        val findings = NoFullyQualifiedNames(Config.empty).compileAndLint(code)

        assertEquals(1, findings.size)
    }

    @Test
    fun `ignores package directives`() {
        val code =
            """
            package foo.bar

            fun test() = Unit
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
