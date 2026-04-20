package io.qent.broxy.core.presetmanagement

object PresetManagementToolNames {
    const val GET_PRESET_CREATION_ALGORITHM = "get_preset_creation_algorithm"
    const val LIST_SERVER_NAMES = "list_server_names"
    const val GET_SERVER_DESCRIPTION = "get_server_description"
    const val LIST_PRESET_NAMES = "list_preset_names"
    const val GET_PRESET_DESCRIPTION = "get_preset_description"
    const val CREATE_PRESET = "create_preset"
    const val LIST_CATALOG_SERVER_NAMES = "list_catalog_server_names"
    const val INSTALL_CATALOG_SERVER = "install_catalog_server"
    const val GET_CATALOG_SERVER_INSTALL_STATUS = "get_catalog_server_install_status"
    const val SET_SERVER_ENABLED = "set_server_enabled"

    val base: List<String> =
        listOf(
            GET_PRESET_CREATION_ALGORITHM,
            LIST_SERVER_NAMES,
            GET_SERVER_DESCRIPTION,
            LIST_PRESET_NAMES,
            GET_PRESET_DESCRIPTION,
            CREATE_PRESET,
        )

    val agentic: List<String> =
        listOf(
            LIST_CATALOG_SERVER_NAMES,
            INSTALL_CATALOG_SERVER,
            GET_CATALOG_SERVER_INSTALL_STATUS,
            SET_SERVER_ENABLED,
        )

    val all: List<String> = base

    val allWithAgentic: List<String> = base + agentic
}
