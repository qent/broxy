package io.qent.broxy.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import io.gitlab.arturbosch.detekt.api.internal.RequiresTypeResolution
import org.jetbrains.kotlin.descriptors.PackageFragmentDescriptor
import org.jetbrains.kotlin.descriptors.PackageViewDescriptor
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import org.jetbrains.kotlin.resolve.BindingContext

@RequiresTypeResolution
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

    private fun KtDotQualifiedExpression.isPackageQualified(): Boolean {
        val leftmost = leftmostReference(this) ?: return false
        return leftmost.isPackageReference()
    }

    private fun KtUserType.isPackageQualified(): Boolean {
        if (qualifier == null) {
            return false
        }
        val leftmost = leftmostTypeReference(this) ?: return false
        return leftmost.isPackageReference()
    }

    private fun leftmostReference(expression: KtExpression): KtReferenceExpression? {
        var current: KtExpression = expression
        while (current is KtQualifiedExpression) {
            current = current.receiverExpression
        }
        return current as? KtReferenceExpression
    }

    private fun leftmostTypeReference(type: KtUserType): KtReferenceExpression? {
        var current: KtUserType? = type
        while (current?.qualifier != null) {
            current = current.qualifier
        }
        return current?.referenceExpression
    }

    private fun KtReferenceExpression.isPackageReference(): Boolean {
        val descriptor = bindingContext[BindingContext.REFERENCE_TARGET, this] ?: return false
        return descriptor is PackageViewDescriptor || descriptor is PackageFragmentDescriptor
    }

    companion object {
        private const val ALLOWED_PREFIXES = "allowedPrefixes"
    }
}
