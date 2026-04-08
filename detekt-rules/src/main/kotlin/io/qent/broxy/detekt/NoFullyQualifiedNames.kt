package io.qent.broxy.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

class NoFullyQualifiedNames(
    config: Config,
) : Rule(config) {
    override val issue: Issue =
        Issue(
            id = "NoFullyQualifiedNames",
            severity = Severity.Style,
            description = "Use imports instead of fully qualified names in code.",
            debt = Debt.FIVE_MINS,
        )

    private val allowedPrefixes: List<String> = valueOrDefault(ALLOWED_PREFIXES, emptyList())

    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        if (expression.getStrictParentOfType<KtImportDirective>() == null &&
            expression.getStrictParentOfType<KtPackageDirective>() == null &&
            !expression.isNestedReceiver() &&
            expression.isPackageQualified() &&
            !isAllowed(expression.text)
        ) {
            report(
                CodeSmell(
                    issue,
                    Entity.from(expression),
                    "Use imports instead of '${expression.text}'.",
                ),
            )
        }
        super.visitDotQualifiedExpression(expression)
    }

    override fun visitUserType(type: KtUserType) {
        if (type.parent !is KtUserType &&
            type.isPackageQualified() &&
            !isAllowed(type.text)
        ) {
            report(
                CodeSmell(
                    issue,
                    Entity.from(type),
                    "Use imports instead of '${type.text}'.",
                ),
            )
        }
        super.visitUserType(type)
    }

    private fun isAllowed(text: String): Boolean {
        val trimmed = text.trim()
        return allowedPrefixes.any { prefix ->
            trimmed == prefix || trimmed.startsWith("$prefix.")
        }
    }

    private fun KtDotQualifiedExpression.isNestedReceiver(): Boolean {
        val parent = parent as? KtDotQualifiedExpression ?: return false
        return parent.receiverExpression == this
    }

    private fun KtDotQualifiedExpression.isPackageQualified(): Boolean = hasPackageLikePrefix(receiverSegments(this))

    private fun KtUserType.isPackageQualified(): Boolean {
        if (qualifier == null) {
            return false
        }
        return hasPackageLikePrefix(typeSegments(this))
    }

    private fun receiverSegments(expression: KtDotQualifiedExpression): List<String> {
        val segments = mutableListOf<String>()
        var current: KtExpression = expression
        while (current is KtQualifiedExpression) {
            val selectorReference = current.selectorExpression as? KtReferenceExpression
            if (selectorReference != null) {
                segments += selectorReference.text
            }
            current = current.receiverExpression
        }
        val rootReference = current as? KtReferenceExpression ?: return emptyList()
        segments += rootReference.text
        return segments.asReversed()
    }

    private fun typeSegments(type: KtUserType): List<String> {
        val segments = mutableListOf<String>()
        var current: KtUserType? = type
        while (current != null) {
            val reference = current.referenceExpression?.text
            if (reference != null) {
                segments += reference
            }
            current = current.qualifier
        }
        return segments.asReversed()
    }

    private fun hasPackageLikePrefix(segments: List<String>): Boolean {
        if (segments.size < 2) {
            return false
        }
        if (!segments[0].isLowercaseIdentifier() || !segments[1].isLowercaseIdentifier()) {
            return false
        }
        if (segments.any { it.isTypeLikeIdentifier() }) {
            return true
        }
        return segments[0] in ROOT_PACKAGE_PREFIXES
    }

    private fun String.isLowercaseIdentifier(): Boolean {
        if (isEmpty() || !first().isLowerCase()) {
            return false
        }
        return all { char ->
            char == '_' || char.isLowerCase() || char.isDigit()
        }
    }

    private fun String.isTypeLikeIdentifier(): Boolean = isNotEmpty() && first().isUpperCase()

    companion object {
        private const val ALLOWED_PREFIXES = "allowedPrefixes"
        private val ROOT_PACKAGE_PREFIXES =
            setOf(
                "java",
                "javax",
                "kotlin",
                "kotlinx",
                "io",
                "org",
                "com",
                "net",
            )
    }
}
