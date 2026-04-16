package io.qent.broxy.registry.catalog

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

private val TEMPLATE_REGEX = Regex("\\{([^{}]+)}")

object CatalogInstallPlanner {
    fun buildServerEntries(servers: List<CatalogServerDetail>): List<CatalogServerEntry> =
        servers
            .map { detail ->
                val profile = selectProfile(detail)
                val runtimeCommand = resolveRuntimeCommand(profile)
                CatalogServerEntry(
                    detail = detail,
                    connectionType = profile?.type,
                    canInstallWithoutInput = canInstallWithoutInput(detail),
                    connectionTypeLabel = profile?.type?.label ?: "Unsupported",
                    capabilities = detail.capabilities(),
                    iconUrl = detail.iconUrl(),
                    runtimeCommand = runtimeCommand,
                    runtimeBinaryName = resolveRuntimeBinaryName(runtimeCommand),
                )
            }.sortedBy { it.detail.displayName().lowercase() }

    fun toServerItems(
        entries: List<CatalogServerEntry>,
        installedServerIds: Set<String>,
    ): List<CatalogServerItem> =
        entries.map { entry ->
            CatalogServerItem(
                id = entry.detail.name,
                title = entry.detail.displayName(),
                canonicalName = entry.detail.name,
                canInstallWithoutInput = entry.canInstallWithoutInput,
                description = entry.detail.description,
                connectionTypeLabel = entry.connectionTypeLabel,
                capabilities = entry.capabilities,
                iconUrl = entry.iconUrl,
                websiteUrl = entry.detail.websiteUrl,
                repositoryUrl = entry.detail.repository?.url,
                installed = entry.detail.name in installedServerIds,
                runtimeCommand = entry.runtimeCommand,
                runtimeBinaryName = entry.runtimeBinaryName,
            )
        }

    fun buildInstallSession(detail: CatalogServerDetail): Result<CatalogInstallSession> =
        runCatching {
            val profile = selectProfile(detail) ?: error("Catalog server '${detail.name}' has no supported install profile")
            val fields = LinkedHashMap<String, CatalogInstallField>()
            collectProfileFields(profile, fields)
            CatalogInstallSession(
                serverId = detail.name,
                defaultName = detail.displayName(),
                transportLabel = profile.type.label,
                connectionType = profile.type,
                detail = detail,
                installSteps = detail.installSteps(),
                fields = fields.values.toList(),
            )
        }

    fun missingRequiredFields(
        session: CatalogInstallSession,
        fieldValues: Map<String, String>,
    ): List<CatalogInstallField> {
        val byId = session.fields.associateBy { it.id }
        return session.fields.filter { field ->
            if (!field.isRequired) return@filter false
            readField(field.id, byId, fieldValues).isBlank()
        }
    }

    fun buildInitialFieldValues(session: CatalogInstallSession): Map<String, String> =
        buildMap {
            session.fields.forEach { field ->
                val defaultValue = field.defaultValue?.trim().orEmpty()
                if (defaultValue.isNotEmpty()) {
                    put(field.id, defaultValue)
                }
            }
        }

    fun requiresInstallForm(
        session: CatalogInstallSession,
        fieldValues: Map<String, String> = buildInitialFieldValues(session),
    ): Boolean = missingRequiredFields(session, fieldValues).isNotEmpty()

    fun buildInstallResult(
        session: CatalogInstallSession,
        displayName: String,
        fieldValues: Map<String, String>,
    ): Result<CatalogInstallResult> =
        runCatching {
            val profile =
                selectProfileByType(session.detail, session.connectionType)
                    ?: error("Catalog profile '${session.connectionType}' is unavailable for '${session.serverId}'")
            val byId = session.fields.associateBy { it.id }
            val missing = missingRequiredFields(session, fieldValues)
            if (missing.isNotEmpty()) {
                error("Missing required fields: ${missing.joinToString { it.label }}")
            }
            val resolvedName = displayName.trim().ifBlank { session.defaultName }
            if (resolvedName.isBlank()) {
                error("Server name cannot be blank")
            }

            val transportDraft =
                when (profile.type) {
                    CatalogConnectionType.StreamableHttp -> {
                        val remote = requireNotNull(profile.remote)
                        RegistryStreamableHttpDraft(
                            url = resolveRemoteUrl(remote, byId, fieldValues),
                            headers = resolveKeyValueInputs("remote.headers", remote.headers, byId, fieldValues),
                        )
                    }

                    CatalogConnectionType.Sse -> {
                        val remote = requireNotNull(profile.remote)
                        RegistryHttpDraft(
                            url = resolveRemoteUrl(remote, byId, fieldValues),
                            headers = resolveKeyValueInputs("remote.headers", remote.headers, byId, fieldValues),
                        )
                    }

                    CatalogConnectionType.StdioPackage -> {
                        val pkg = requireNotNull(profile.pkg)
                        val command = pkg.runtimeHint?.trim().orEmpty()
                        if (command.isBlank()) {
                            error("Package profile requires runtimeHint")
                        }
                        val args = buildPackageArgs(pkg, byId, fieldValues)
                        RegistryStdioDraft(command = command, args = args)
                    }
                }

            val env =
                if (profile.type == CatalogConnectionType.StdioPackage) {
                    resolveKeyValueInputs(
                        prefix = "package.env",
                        inputs = requireNotNull(profile.pkg).environmentVariables,
                        fields = byId,
                        values = fieldValues,
                    )
                } else {
                    emptyMap()
                }
            val auth =
                when (profile.type) {
                    CatalogConnectionType.StreamableHttp,
                    CatalogConnectionType.Sse,
                    -> resolveRemoteOAuth(requireNotNull(profile.remote), byId, fieldValues)

                    CatalogConnectionType.StdioPackage -> null
                }

            CatalogInstallResult(
                draft =
                    RegistryServerDraft(
                        id = session.serverId,
                        name = resolvedName,
                        enabled = true,
                        transport = transportDraft,
                        env = env,
                        auth = auth,
                    ),
            )
        }

    private fun buildPackageArgs(
        pkg: CatalogPackage,
        fields: Map<String, CatalogInstallField>,
        values: Map<String, String>,
    ): List<String> {
        val args = mutableListOf<String>()
        args += resolveArguments("package.runtimeArguments", pkg.runtimeArguments, fields, values)
        args += packageSpecifier(pkg)
        args += resolveArguments("package.packageArguments", pkg.packageArguments, fields, values)
        return args
    }

    private fun packageSpecifier(pkg: CatalogPackage): String {
        val identifier = pkg.identifier.trim()
        val version = pkg.version?.trim().orEmpty()
        if (version.isEmpty()) {
            return identifier
        }
        return when (pkg.registryType.trim().lowercase()) {
            "npm" -> "$identifier@$version"
            "pypi" -> "$identifier==$version"
            "nuget" -> "$identifier@$version"
            else -> identifier
        }
    }

    private fun resolveArguments(
        prefix: String,
        arguments: List<CatalogArgument>,
        fields: Map<String, CatalogInstallField>,
        values: Map<String, String>,
    ): List<String> {
        val resolved = mutableListOf<String>()
        arguments.forEachIndexed { index, arg ->
            val scope = argumentScope(prefix, index, arg)
            val value = resolveArgumentValue(scope, arg, fields, values)
            when (arg.type.trim().lowercase()) {
                "named" -> {
                    val name = arg.name?.trim().orEmpty()
                    if (name.isEmpty()) return@forEachIndexed
                    if (value.isBlank()) {
                        if (arg.isRequired) {
                            error("Argument '$name' is required")
                        }
                        if (arg.value != null) {
                            resolved += name
                        }
                    } else {
                        val valuesToApply = splitRepeated(value, arg.isRepeated)
                        valuesToApply.forEach { entry ->
                            resolved += name
                            resolved += entry
                        }
                    }
                }

                else -> {
                    if (value.isBlank()) {
                        if (arg.isRequired) {
                            val label = arg.valueHint?.trim().takeUnless { it.isNullOrEmpty() } ?: "positional-$index"
                            error("Argument '$label' is required")
                        }
                    } else {
                        resolved += splitRepeated(value, arg.isRepeated)
                    }
                }
            }
        }
        return resolved
    }

    private fun splitRepeated(
        value: String,
        repeated: Boolean,
    ): List<String> {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (!repeated) return listOf(trimmed)
        return trimmed
            .split(',')
            .mapNotNull { token -> token.trim().takeIf { it.isNotEmpty() } }
    }

    private fun resolveRemoteUrl(
        remote: CatalogRemoteTransport,
        fields: Map<String, CatalogInstallField>,
        values: Map<String, String>,
    ): String {
        val rendered =
            renderTemplate(
                template = remote.url,
                scope = "remote.url",
                variables = remote.variables,
                fields = fields,
                values = values,
            )
        return rendered.trim().also {
            if (it.isEmpty()) {
                error("Remote URL is empty")
            }
        }
    }

    private fun resolveRemoteOAuth(
        remote: CatalogRemoteTransport,
        fields: Map<String, CatalogInstallField>,
        values: Map<String, String>,
    ): RegistryOAuthDraft? {
        val oauth = remote.oauth ?: return null
        return RegistryOAuthDraft(
            clientId =
                resolveOAuthString(
                    value = oauth.clientId,
                    remote = remote,
                    fields = fields,
                    values = values,
                ),
            clientSecret =
                resolveOAuthString(
                    value = oauth.clientSecret,
                    remote = remote,
                    fields = fields,
                    values = values,
                ),
            callbackPort =
                resolveOAuthCallbackPort(
                    value = oauth.callbackPort,
                    remote = remote,
                    fields = fields,
                    values = values,
                ),
            clientIdMetadataUrl =
                resolveOAuthString(
                    value = oauth.clientIdMetadataUrl,
                    remote = remote,
                    fields = fields,
                    values = values,
                ),
            authServerMetadataUrl =
                resolveOAuthString(
                    value = oauth.authServerMetadataUrl,
                    remote = remote,
                    fields = fields,
                    values = values,
                ),
            redirectUri =
                resolveOAuthString(
                    value = oauth.redirectUri,
                    remote = remote,
                    fields = fields,
                    values = values,
                ),
            clientName =
                resolveOAuthString(
                    value = oauth.clientName,
                    remote = remote,
                    fields = fields,
                    values = values,
                ),
            tokenEndpointAuthMethod =
                resolveOAuthString(
                    value = oauth.tokenEndpointAuthMethod,
                    remote = remote,
                    fields = fields,
                    values = values,
                ),
            authorizationServer =
                resolveOAuthString(
                    value = oauth.authorizationServer,
                    remote = remote,
                    fields = fields,
                    values = values,
                ),
            scopes = resolveOAuthScopes(oauth.scopes, remote, fields, values),
            allowDynamicRegistration = oauth.allowDynamicRegistration,
        )
    }

    private fun resolveOAuthString(
        value: String?,
        remote: CatalogRemoteTransport,
        fields: Map<String, CatalogInstallField>,
        values: Map<String, String>,
    ): String? {
        val raw = value?.trim() ?: return null
        if (raw.isEmpty()) return null
        val rendered =
            renderTemplate(
                template = raw,
                scope = "remote.url",
                variables = remote.variables,
                fields = fields,
                values = values,
            ).trim()
        return rendered.takeIf { it.isNotEmpty() }
    }

    private fun resolveOAuthScopes(
        scopes: List<String>?,
        remote: CatalogRemoteTransport,
        fields: Map<String, CatalogInstallField>,
        values: Map<String, String>,
    ): List<String>? {
        val resolved =
            scopes
                ?.map { scope ->
                    renderTemplate(
                        template = scope,
                        scope = "remote.url",
                        variables = remote.variables,
                        fields = fields,
                        values = values,
                    ).trim()
                }?.filter { it.isNotEmpty() }
                .orEmpty()
        return resolved.ifEmpty { null }
    }

    private fun resolveOAuthCallbackPort(
        value: JsonElement?,
        remote: CatalogRemoteTransport,
        fields: Map<String, CatalogInstallField>,
        values: Map<String, String>,
    ): Int? {
        val primitive = value as? JsonPrimitive ?: return null
        val rendered =
            renderTemplate(
                template = primitive.content.trim(),
                scope = "remote.url",
                variables = remote.variables,
                fields = fields,
                values = values,
            ).trim()
        if (rendered.isEmpty()) return null
        return rendered.toIntOrNull() ?: error("OAuth callbackPort must be an integer, got '$rendered'")
    }

    private fun resolveKeyValueInputs(
        prefix: String,
        inputs: List<CatalogKeyValueInput>,
        fields: Map<String, CatalogInstallField>,
        values: Map<String, String>,
    ): Map<String, String> {
        val mapped = linkedMapOf<String, String>()
        inputs.forEachIndexed { index, input ->
            val key = input.name.trim()
            if (key.isEmpty()) return@forEachIndexed
            val scope = "$prefix.$index.${sanitizeKey(key)}"
            val resolved = resolveKeyValueInput(scope, input, fields, values)
            if (resolved.isBlank()) {
                if (input.isRequired) {
                    error("Field '$key' is required")
                }
                return@forEachIndexed
            }
            mapped[key] = resolved
        }
        return mapped
    }

    private fun resolveKeyValueInput(
        scope: String,
        input: CatalogKeyValueInput,
        fields: Map<String, CatalogInstallField>,
        values: Map<String, String>,
    ): String {
        val explicit = input.value?.trim()
        return if (!explicit.isNullOrEmpty()) {
            renderTemplate(explicit, scope, input.variables, fields, values).trim()
        } else {
            readField(scope, fields, values)
        }
    }

    private fun resolveArgumentValue(
        scope: String,
        argument: CatalogArgument,
        fields: Map<String, CatalogInstallField>,
        values: Map<String, String>,
    ): String {
        val explicit = argument.value?.trim()
        return if (!explicit.isNullOrEmpty()) {
            renderTemplate(explicit, scope, argument.variables, fields, values).trim()
        } else {
            readField(scope, fields, values)
        }
    }

    private fun renderTemplate(
        template: String,
        scope: String,
        variables: Map<String, CatalogInput>,
        fields: Map<String, CatalogInstallField>,
        values: Map<String, String>,
    ): String {
        if (variables.isEmpty()) {
            return template
        }
        return TEMPLATE_REGEX.replace(template) { match ->
            val key = match.groupValues[1].trim()
            if (key.isEmpty()) return@replace match.value
            val variable = variables[key] ?: return@replace match.value
            resolveVariableValue(scope, key, variable, fields, values)
        }
    }

    private fun resolveVariableValue(
        scope: String,
        variableName: String,
        variable: CatalogInput,
        fields: Map<String, CatalogInstallField>,
        values: Map<String, String>,
    ): String {
        val explicit = variable.value?.trim()
        if (!explicit.isNullOrEmpty()) {
            return explicit
        }
        val fieldId = variableFieldId(scope, variableName)
        return readField(fieldId, fields, values)
    }

    private fun readField(
        fieldId: String,
        fields: Map<String, CatalogInstallField>,
        values: Map<String, String>,
    ): String {
        val provided = values[fieldId]?.trim()
        if (!provided.isNullOrEmpty()) {
            return provided
        }
        val fallback = fields[fieldId]?.defaultValue?.trim()
        return fallback.orEmpty()
    }

    private fun collectProfileFields(
        profile: SupportedProfile,
        fields: MutableMap<String, CatalogInstallField>,
    ) {
        when (profile.type) {
            CatalogConnectionType.StreamableHttp,
            CatalogConnectionType.Sse,
            -> {
                val remote = requireNotNull(profile.remote)
                remote.variables
                    .toSortedMap()
                    .forEach { (name, input) ->
                        val scope = variableFieldId("remote.url", name)
                        addInputField(
                            fields = fields,
                            fieldId = scope,
                            label = name,
                            input = input,
                            isRepeated = false,
                        )
                    }
                remote.headers.forEachIndexed { index, header ->
                    val key = header.name.trim()
                    if (key.isEmpty()) return@forEachIndexed
                    val scope = "remote.headers.$index.${sanitizeKey(key)}"
                    collectKeyValueField(fields, scope, key, header)
                }
            }

            CatalogConnectionType.StdioPackage -> {
                val pkg = requireNotNull(profile.pkg)
                pkg.environmentVariables.forEachIndexed { index, env ->
                    val key = env.name.trim()
                    if (key.isEmpty()) return@forEachIndexed
                    val scope = "package.env.$index.${sanitizeKey(key)}"
                    collectKeyValueField(fields, scope, key, env)
                }
                pkg.runtimeArguments.forEachIndexed { index, arg ->
                    collectArgumentField(fields, argumentScope("package.runtimeArguments", index, arg), arg)
                }
                pkg.packageArguments.forEachIndexed { index, arg ->
                    collectArgumentField(fields, argumentScope("package.packageArguments", index, arg), arg)
                }
            }
        }
    }

    private fun collectKeyValueField(
        fields: MutableMap<String, CatalogInstallField>,
        scope: String,
        label: String,
        input: CatalogKeyValueInput,
    ) {
        if (input.value.isNullOrBlank()) {
            fields.putIfAbsent(
                scope,
                CatalogInstallField(
                    id = scope,
                    label = label,
                    description = input.description,
                    format = parseFormat(input.format),
                    isRequired = input.isRequired,
                    isSecret = input.isSecret,
                    isRepeated = false,
                    choices = input.choices,
                    placeholder = input.placeholder,
                    defaultValue = input.default,
                ),
            )
        }
        input.variables
            .toSortedMap()
            .forEach { (name, variable) ->
                addInputField(
                    fields = fields,
                    fieldId = variableFieldId(scope, name),
                    label = label,
                    input = variable,
                    isRepeated = false,
                )
            }
    }

    private fun collectArgumentField(
        fields: MutableMap<String, CatalogInstallField>,
        scope: String,
        argument: CatalogArgument,
    ) {
        val label =
            when (argument.type.trim().lowercase()) {
                "named" ->
                    argument.name
                        ?.trim()
                        .orEmpty()
                        .ifEmpty { scope.substringAfterLast('.') }

                else ->
                    argument.valueHint
                        ?.trim()
                        .takeUnless { it.isNullOrEmpty() }
                        ?: scope.substringAfterLast('.')
            }
        if (argument.value.isNullOrBlank()) {
            fields.putIfAbsent(
                scope,
                CatalogInstallField(
                    id = scope,
                    label = label,
                    description = argument.description,
                    format = parseFormat(argument.format),
                    isRequired = argument.isRequired,
                    isSecret = argument.isSecret,
                    isRepeated = argument.isRepeated,
                    choices = argument.choices,
                    placeholder = argument.placeholder,
                    defaultValue = argument.default,
                ),
            )
        }
        argument.variables
            .toSortedMap()
            .forEach { (name, variable) ->
                addInputField(
                    fields = fields,
                    fieldId = variableFieldId(scope, name),
                    label = name,
                    input = variable,
                    isRepeated = false,
                )
            }
    }

    private fun addInputField(
        fields: MutableMap<String, CatalogInstallField>,
        fieldId: String,
        label: String,
        input: CatalogInput,
        isRepeated: Boolean,
    ) {
        fields.putIfAbsent(
            fieldId,
            CatalogInstallField(
                id = fieldId,
                label = label,
                description = input.description,
                format = parseFormat(input.format),
                isRequired = input.isRequired,
                isSecret = input.isSecret,
                isRepeated = isRepeated,
                choices = input.choices,
                placeholder = input.placeholder,
                defaultValue = input.default,
            ),
        )
    }

    private fun argumentScope(
        prefix: String,
        index: Int,
        argument: CatalogArgument,
    ): String {
        val suffix =
            when (argument.type.trim().lowercase()) {
                "named" ->
                    sanitizeKey(
                        argument.name
                            ?.trim()
                            .orEmpty()
                            .ifEmpty { "named-$index" },
                    )
                else ->
                    sanitizeKey(
                        argument.valueHint
                            ?.trim()
                            .orEmpty()
                            .ifEmpty { "positional-$index" },
                    )
            }
        return "$prefix.$index.$suffix"
    }

    private fun variableFieldId(
        scope: String,
        variableName: String,
    ): String = "$scope.var.${sanitizeKey(variableName)}"

    private fun parseFormat(raw: String?): CatalogFieldFormat =
        when (raw?.trim()?.lowercase()) {
            "number" -> CatalogFieldFormat.Number
            "boolean" -> CatalogFieldFormat.Boolean
            "filepath" -> CatalogFieldFormat.Filepath
            else -> CatalogFieldFormat.String
        }

    private fun sanitizeKey(raw: String): String =
        raw
            .trim()
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifEmpty { "field" }

    private fun resolveRuntimeCommand(profile: SupportedProfile?): String? {
        if (profile?.type != CatalogConnectionType.StdioPackage) {
            return null
        }
        return profile
            .pkg
            ?.runtimeHint
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun resolveRuntimeBinaryName(runtimeCommand: String?): String? {
        val token =
            runtimeCommand
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.split(Regex("\\s+"), limit = 2)
                ?.firstOrNull()
                ?.trim(' ', '"', '\'')
                .orEmpty()
        if (token.isEmpty()) {
            return null
        }
        val binaryName = token.substringAfterLast('/').substringAfterLast('\\').trim()
        return binaryName.takeIf { it.isNotEmpty() }
    }

    private fun canInstallWithoutInput(detail: CatalogServerDetail): Boolean {
        val session = buildInstallSession(detail).getOrNull() ?: return false
        if (session.installSteps.isNotEmpty()) {
            return false
        }
        return !requiresInstallForm(session)
    }

    private fun selectProfile(detail: CatalogServerDetail): SupportedProfile? {
        val streamable = detail.remotes.firstOrNull { it.type.trim().equals("streamable-http", ignoreCase = true) }
        if (streamable != null) {
            return SupportedProfile(type = CatalogConnectionType.StreamableHttp, remote = streamable, pkg = null)
        }
        val sse = detail.remotes.firstOrNull { it.type.trim().equals("sse", ignoreCase = true) }
        if (sse != null) {
            return SupportedProfile(type = CatalogConnectionType.Sse, remote = sse, pkg = null)
        }
        val stdioPackage =
            detail.packages.firstOrNull { pkg ->
                pkg.transport.type
                    .trim()
                    .equals("stdio", ignoreCase = true) &&
                    !pkg.runtimeHint.isNullOrBlank()
            }
        if (stdioPackage != null) {
            return SupportedProfile(type = CatalogConnectionType.StdioPackage, remote = null, pkg = stdioPackage)
        }
        return null
    }

    private fun selectProfileByType(
        detail: CatalogServerDetail,
        type: CatalogConnectionType,
    ): SupportedProfile? =
        when (type) {
            CatalogConnectionType.StreamableHttp ->
                detail.remotes
                    .firstOrNull { it.type.trim().equals("streamable-http", ignoreCase = true) }
                    ?.let { SupportedProfile(type = type, remote = it, pkg = null) }

            CatalogConnectionType.Sse ->
                detail.remotes
                    .firstOrNull { it.type.trim().equals("sse", ignoreCase = true) }
                    ?.let { SupportedProfile(type = type, remote = it, pkg = null) }

            CatalogConnectionType.StdioPackage ->
                detail.packages
                    .firstOrNull { pkg ->
                        pkg.transport.type
                            .trim()
                            .equals("stdio", ignoreCase = true) &&
                            !pkg.runtimeHint.isNullOrBlank()
                    }?.let { SupportedProfile(type = type, remote = null, pkg = it) }
        }

    private data class SupportedProfile(
        val type: CatalogConnectionType,
        val remote: CatalogRemoteTransport?,
        val pkg: CatalogPackage?,
    )
}
