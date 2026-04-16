package io.qent.broxy.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class UnreferencedDeclaration(
    config: Config,
) : Rule(config) {
    override val issue: Issue =
        Issue(
            id = "UnreferencedDeclaration",
            severity = Severity.Defect,
            description = "Finds internal/public declarations that are not referenced inside repository sources.",
            debt = Debt.TEN_MINS,
        )

    private val useRepositoryIndex: Boolean = valueOrDefault(USE_REPOSITORY_INDEX, true)
    private val includePublicDeclarations: Boolean = valueOrDefault(INCLUDE_PUBLIC_DECLARATIONS, true)
    private val includeInternalDeclarations: Boolean = valueOrDefault(INCLUDE_INTERNAL_DECLARATIONS, true)
    private val includeTestSources: Boolean = valueOrDefault(INCLUDE_TEST_SOURCES, false)
    private val ignoreOverriddenDeclarations: Boolean = valueOrDefault(IGNORE_OVERRIDDEN_DECLARATIONS, true)
    private val allowlistSimpleNames: List<String> = valueOrDefault(ALLOWLIST_SIMPLE_NAMES, listOf("main"))
    private val allowlistFqNames: List<String> = valueOrDefault(ALLOWLIST_FQ_NAMES, emptyList())
    private val allowlistFqNamePrefixes: List<String> = valueOrDefault(ALLOWLIST_FQ_NAME_PREFIXES, emptyList())
    private val excludedPathRegexes: List<Regex> =
        valueOrDefault(EXCLUDED_PATH_REGEXES, emptyList<String>()).map { it.toRegex() }
    private val repositoryRootOverride: String = valueOrDefault(REPOSITORY_ROOT, "")

    private val repositoryReferenceIndex: Map<String, Int> by lazy {
        if (!useRepositoryIndex) {
            emptyMap()
        } else {
            loadRepositoryReferenceIndex()
        }
    }

    override fun visitNamedFunction(function: KtNamedFunction) {
        inspectDeclaration(function)
        super.visitNamedFunction(function)
    }

    override fun visitProperty(property: KtProperty) {
        inspectDeclaration(property)
        super.visitProperty(property)
    }

    private fun inspectDeclaration(declaration: KtNamedDeclaration) {
        val name = declaration.name ?: return
        if (!isVisibilityEnabledFor(declaration)) return
        if (!isDeclarationScopeSupported(declaration)) return
        if (ignoreOverriddenDeclarations && declaration.hasModifier(KtTokens.OVERRIDE_KEYWORD)) return
        if (allowlistSimpleNames.contains(name)) return

        val fqName = buildFqName(declaration)
        if (fqName != null && isAllowlistedByFqName(fqName)) return

        val references = countReferences(name, declaration.containingKtFile)
        if (references <= DECLARATION_ONLY_REFERENCE_COUNT) {
            val reportedName = fqName ?: name
            report(
                CodeSmell(
                    issue,
                    Entity.from(declaration),
                    "Declaration '$reportedName' has no references in repository sources; remove it or add it to allowlist.",
                ),
            )
        }
    }

    private fun isVisibilityEnabledFor(declaration: KtNamedDeclaration): Boolean =
        when {
            declaration.hasModifier(KtTokens.PRIVATE_KEYWORD) -> false
            declaration.hasModifier(KtTokens.PROTECTED_KEYWORD) -> false
            declaration.hasModifier(KtTokens.INTERNAL_KEYWORD) -> includeInternalDeclarations
            else -> includePublicDeclarations
        }

    private fun isDeclarationScopeSupported(declaration: KtNamedDeclaration): Boolean {
        if (declaration.getStrictParentOfType<KtNamedFunction>() != null) return false
        val isTopLevel = declaration.parent is KtFile
        val insideClassLike = declaration.getStrictParentOfType<KtClassOrObject>() != null
        return isTopLevel || insideClassLike
    }

    private fun isAllowlistedByFqName(fqName: String): Boolean {
        if (allowlistFqNames.contains(fqName)) {
            return true
        }
        return allowlistFqNamePrefixes.any { prefix ->
            fqName == prefix || fqName.startsWith("$prefix.")
        }
    }

    private fun buildFqName(declaration: KtNamedDeclaration): String? {
        val name = declaration.name ?: return null
        val packageName =
            declaration
                .containingKtFile
                .packageFqName
                .asString()
                .takeIf { it.isNotBlank() }
        val ownerNames =
            generateSequence(declaration.parent) { it.parent }
                .filterIsInstance<KtNamedDeclaration>()
                .mapNotNull { it.name }
                .toList()
                .asReversed()

        return buildString {
            if (packageName != null) {
                append(packageName)
                append('.')
            }
            if (ownerNames.isNotEmpty()) {
                append(ownerNames.joinToString("."))
                append('.')
            }
            append(name)
        }
    }

    private fun countReferences(
        name: String,
        containingFile: KtFile,
    ): Int =
        if (useRepositoryIndex) {
            repositoryReferenceIndex[name] ?: 0
        } else {
            tokenizeIdentifiers(containingFile.text)[name] ?: 0
        }

    private fun loadRepositoryReferenceIndex(): Map<String, Int> {
        val root = resolveRepositoryRoot() ?: return emptyMap()
        if (!Files.exists(root)) return emptyMap()

        val counts = mutableMapOf<String, Int>()
        Files.walk(root).use { paths ->
            paths
                .filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().endsWith(".kt") }
                .filter(::shouldIndexFile)
                .forEach { path ->
                    val text = runCatching { Files.readString(path) }.getOrDefault("")
                    for ((identifier, count) in tokenizeIdentifiers(text)) {
                        counts[identifier] = (counts[identifier] ?: 0) + count
                    }
                }
        }
        return counts
    }

    private fun resolveRepositoryRoot(): Path? {
        if (repositoryRootOverride.isNotBlank()) {
            return Paths.get(repositoryRootOverride).toAbsolutePath().normalize()
        }

        val start = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        var current = start
        while (true) {
            if (Files.exists(current.resolve(".git"))) {
                return current
            }
            val parent = current.parent ?: break
            current = parent
        }
        return start
    }

    private fun shouldIndexFile(path: Path): Boolean {
        val normalizedPath =
            path
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace('\\', '/')
        if (DEFAULT_EXCLUDED_PATH_FRAGMENTS.any { normalizedPath.contains(it) }) {
            return false
        }
        if (!includeTestSources && TEST_SOURCE_PATH_FRAGMENTS.any { normalizedPath.contains(it) }) {
            return false
        }
        return excludedPathRegexes.none { regex -> regex.containsMatchIn(normalizedPath) }
    }

    private fun tokenizeIdentifiers(text: String): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        IDENTIFIER_REGEX.findAll(text).forEach { match ->
            val token = match.value
            counts[token] = (counts[token] ?: 0) + 1
        }
        return counts
    }

    companion object {
        private const val USE_REPOSITORY_INDEX = "useRepositoryIndex"
        private const val INCLUDE_PUBLIC_DECLARATIONS = "includePublicDeclarations"
        private const val INCLUDE_INTERNAL_DECLARATIONS = "includeInternalDeclarations"
        private const val INCLUDE_TEST_SOURCES = "includeTestSources"
        private const val IGNORE_OVERRIDDEN_DECLARATIONS = "ignoreOverriddenDeclarations"
        private const val ALLOWLIST_SIMPLE_NAMES = "allowlistSimpleNames"
        private const val ALLOWLIST_FQ_NAMES = "allowlistFqNames"
        private const val ALLOWLIST_FQ_NAME_PREFIXES = "allowlistFqNamePrefixes"
        private const val EXCLUDED_PATH_REGEXES = "excludedPathRegexes"
        private const val REPOSITORY_ROOT = "repositoryRoot"
        private const val DECLARATION_ONLY_REFERENCE_COUNT = 1

        private val IDENTIFIER_REGEX = Regex("\\b[A-Za-z_][A-Za-z0-9_]*\\b")
        private val DEFAULT_EXCLUDED_PATH_FRAGMENTS = listOf("/build/")
        private val TEST_SOURCE_PATH_FRAGMENTS =
            listOf(
                "/src/test/",
                "/src/androidTest/",
                "/src/commonTest/",
                "/src/jvmTest/",
                "/src/jsTest/",
                "/src/iosTest/",
                "/src/desktopTest/",
                "/src/integrationTest/",
            )
    }
}
