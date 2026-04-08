package io.qent.broxy.ui.adapter.data

data class FilePickRequest(
    val title: String,
    val initialPath: String? = null,
    val allowedExtensions: Set<String> = emptySet(),
)

interface SystemPicker {
    fun pickDirectory(initialPath: String?): Result<String?>

    fun pickFile(request: FilePickRequest): Result<String?>

    companion object {
        val Noop: SystemPicker =
            object : SystemPicker {
                override fun pickDirectory(initialPath: String?): Result<String?> = Result.success(null)

                override fun pickFile(request: FilePickRequest): Result<String?> = Result.success(null)
            }
    }
}
