package io.qent.broxy.ui.adapter.icons

interface ServerIconRepository {
    suspend fun pickAndImportIcon(): Result<String?>

    suspend fun deleteIcon(iconPath: String): Result<Unit>

    companion object {
        val Noop: ServerIconRepository =
            object : ServerIconRepository {
                override suspend fun pickAndImportIcon(): Result<String?> = Result.success(null)

                override suspend fun deleteIcon(iconPath: String): Result<Unit> = Result.success(Unit)
            }
    }
}
