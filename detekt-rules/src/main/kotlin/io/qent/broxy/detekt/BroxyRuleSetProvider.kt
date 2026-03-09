package io.qent.broxy.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetId
import io.gitlab.arturbosch.detekt.api.RuleSetProvider

class BroxyRuleSetProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = "broxy"

    override fun instance(config: Config): RuleSet =
        RuleSet(
            ruleSetId,
            listOf(NoFullyQualifiedNames(config)),
        )
}
