package io.qent.broxy.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.PackageFragmentDescriptor
import org.jetbrains.kotlin.descriptors.PropertyAccessorDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import org.jetbrains.kotlin.resolve.BindingContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.IdentityHashMap

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
    private val repositoryQualifiedReferenceIndex: Map<QualifiedReferenceKey, Int> by lazy {
        if (!useRepositoryIndex) {
            emptyMap()
        } else {
            loadRepositoryQualifiedReferenceIndex()
        }
    }
    private val resolvedReferenceIndexByContext: IdentityHashMap<BindingContext, Map<String, Int>> = IdentityHashMap()

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
        val symbolKey = buildSymbolKey(declaration)
        val hasReferences =
            if (symbolKey != null && bindingContext != BindingContext.EMPTY) {
                val resolved = countResolvedReferences(symbolKey)
                resolved > DECLARATION_ONLY_REFERENCE_COUNT ||
                    hasReferencesByTextFallback(name, declaration, declaration.containingKtFile)
            } else {
                hasReferencesByTextFallback(name, declaration, declaration.containingKtFile)
            }
        if (!hasReferences) {
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

    private fun countResolvedReferences(symbolKey: String): Int {
        val context = bindingContext
        if (context == BindingContext.EMPTY) {
            return 0
        }
        val index = resolvedReferenceIndexByContext.getOrPut(context) { loadResolvedReferenceIndex(context) }
        return index[symbolKey] ?: 0
    }

    private fun loadResolvedReferenceIndex(context: BindingContext): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()

        context.getSliceContents(BindingContext.REFERENCE_TARGET).forEach { (referenceExpression, descriptor) ->
            if (!shouldIndexElement(referenceExpression)) return@forEach
            if (referenceExpression.getStrictParentOfType<KtImportDirective>() != null) return@forEach
            val key = (descriptor as? CallableDescriptor)?.toSymbolKey() ?: return@forEach
            counts[key] = (counts[key] ?: 0) + 1
        }

        context.getSliceContents(BindingContext.RESOLVED_CALL).forEach { (_, resolvedCall) ->
            val callElement = resolvedCall.call.callElement
            if (!shouldIndexElement(callElement)) return@forEach
            if (callElement.getStrictParentOfType<KtImportDirective>() != null) return@forEach
            val key = resolvedCall.resultingDescriptor.toSymbolKey() ?: return@forEach
            counts[key] = (counts[key] ?: 0) + 1
        }

        return counts
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

    private fun buildSymbolKey(declaration: KtNamedDeclaration): String? {
        val descriptor = bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, declaration] as? CallableDescriptor
        if (descriptor != null) {
            return descriptor.toSymbolKey()
        }
        return buildPsiFallbackSymbolKey(declaration)
    }

    private fun buildPsiFallbackSymbolKey(declaration: KtNamedDeclaration): String? {
        val declarationName = declaration.name ?: return null
        val packageName = declaration.containingKtFile.packageFqName.asString()
        val owners =
            generateSequence(declaration.parent) { it.parent }
                .filterIsInstance<KtNamedDeclaration>()
                .mapNotNull { owner -> owner.name?.takeUnless { it.isSyntheticName() } }
                .toList()
                .asReversed()
        val callableKind =
            when (declaration) {
                is KtNamedFunction -> CallableKind.FUNCTION
                is KtProperty -> CallableKind.PROPERTY
                else -> return null
            }
        val parameterCount = (declaration as? KtNamedFunction)?.valueParameters?.size ?: 0
        return SymbolKey(
            packageName = packageName,
            owners = owners,
            callableName = declarationName,
            callableKind = callableKind,
            parameterCount = parameterCount,
        ).serialize()
    }

    private fun CallableDescriptor.toSymbolKey(): String? {
        val callableDescriptor: CallableDescriptor =
            when (this) {
                is PropertyAccessorDescriptor -> correspondingProperty
                else -> this
            }
        val callableName = callableDescriptor.name.asString()
        if (callableName.isSyntheticName()) {
            return null
        }

        val owners = mutableListOf<String>()
        var current: DeclarationDescriptor? = callableDescriptor.containingDeclaration
        var packageName = ""
        while (current != null) {
            when (current) {
                is PackageFragmentDescriptor -> {
                    packageName = current.fqName.asString()
                    break
                }
                else -> {
                    val ownerName = current.name.asString()
                    if (!ownerName.isSyntheticName()) {
                        owners += ownerName
                    }
                    current = current.containingDeclaration
                }
            }
        }
        owners.reverse()

        val callableKind =
            when (callableDescriptor) {
                is PropertyDescriptor -> CallableKind.PROPERTY
                else -> CallableKind.FUNCTION
            }
        val parameterCount = (callableDescriptor as? FunctionDescriptor)?.valueParameters?.size ?: 0
        return SymbolKey(
            packageName = packageName,
            owners = owners,
            callableName = callableName,
            callableKind = callableKind,
            parameterCount = parameterCount,
        ).serialize()
    }

    private fun String.isSyntheticName(): Boolean = startsWith("<") || isBlank()

    private fun hasReferencesByTextFallback(
        name: String,
        declaration: KtNamedDeclaration,
        containingFile: KtFile,
    ): Boolean {
        val companionHostName = declaration.companionHostClassName()
        if (companionHostName != null) {
            val qualifiedRefs =
                if (useRepositoryIndex) {
                    repositoryQualifiedReferenceIndex[QualifiedReferenceKey(companionHostName, name)] ?: 0
                } else {
                    countQualifiedReferencesInText(containingFile.text, companionHostName, name)
                }
            return qualifiedRefs > 0
        }
        val references = countReferencesByName(name, containingFile)
        return references > DECLARATION_ONLY_REFERENCE_COUNT
    }

    private fun KtNamedDeclaration.companionHostClassName(): String? {
        val companionOwner = getStrictParentOfType<KtObjectDeclaration>() ?: return null
        if (!companionOwner.isCompanion()) return null
        return companionOwner.getStrictParentOfType<KtClassOrObject>()?.name
    }

    private fun countReferencesByName(
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

    private fun loadRepositoryQualifiedReferenceIndex(): Map<QualifiedReferenceKey, Int> {
        val root = resolveRepositoryRoot() ?: return emptyMap()
        if (!Files.exists(root)) return emptyMap()

        val counts = mutableMapOf<QualifiedReferenceKey, Int>()
        Files.walk(root).use { paths ->
            paths
                .filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().endsWith(".kt") }
                .filter(::shouldIndexFile)
                .forEach { path ->
                    val text = runCatching { Files.readString(path) }.getOrDefault("")
                    for ((key, count) in tokenizeQualifiedReferences(text)) {
                        counts[key] = (counts[key] ?: 0) + count
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

    private fun shouldIndexElement(element: KtElement): Boolean {
        val path = element.containingFile.virtualFile?.path ?: return true
        return shouldIndexPath(path)
    }

    private fun shouldIndexFile(path: Path): Boolean {
        val normalizedPath =
            path
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace('\\', '/')
        return shouldIndexPath(normalizedPath)
    }

    private fun shouldIndexPath(path: String): Boolean {
        val normalizedPath = path.replace('\\', '/')
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

    private fun countQualifiedReferencesInText(
        text: String,
        owner: String,
        member: String,
    ): Int = tokenizeQualifiedReferences(text)[QualifiedReferenceKey(owner, member)] ?: 0

    private fun tokenizeQualifiedReferences(text: String): Map<QualifiedReferenceKey, Int> {
        val counts = mutableMapOf<QualifiedReferenceKey, Int>()
        QUALIFIED_REFERENCE_REGEX.findAll(text).forEach { match ->
            val owner = match.groupValues[1]
            val member = match.groupValues[2]
            val key = QualifiedReferenceKey(owner, member)
            counts[key] = (counts[key] ?: 0) + 1
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
        private val QUALIFIED_REFERENCE_REGEX = Regex("\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\.\\s*([A-Za-z_][A-Za-z0-9_]*)\\b")
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

    private enum class CallableKind {
        FUNCTION,
        PROPERTY,
    }

    private data class SymbolKey(
        val packageName: String,
        val owners: List<String>,
        val callableName: String,
        val callableKind: CallableKind,
        val parameterCount: Int,
    ) {
        fun serialize(): String {
            val ownersPart = owners.joinToString(".")
            return "$packageName|$ownersPart|$callableName|${callableKind.name}|$parameterCount"
        }
    }

    private data class QualifiedReferenceKey(
        val owner: String,
        val member: String,
    )
}
