package io.qent.broxy.core.presetmanagement

object PresetManagementToolNames {
    const val GET_PRESET_CREATION_ALGORITHM = "get_preset_creation_algorithm"
    const val LIST_SERVER_NAMES = "list_server_names"
    const val GET_SERVER_DESCRIPTION = "get_server_description"
    const val LIST_PRESET_NAMES = "list_preset_names"
    const val GET_PRESET_DESCRIPTION = "get_preset_description"
    const val CREATE_PRESET = "create_preset"

    val all: List<String> =
        listOf(
            GET_PRESET_CREATION_ALGORITHM,
            LIST_SERVER_NAMES,
            GET_SERVER_DESCRIPTION,
            LIST_PRESET_NAMES,
            GET_PRESET_DESCRIPTION,
            CREATE_PRESET,
        )
}
