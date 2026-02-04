package io.qent.broxy.core.utils

import kotlin.test.Test
import kotlin.test.assertEquals

class PathEntriesTest {
    @Test
    fun `parsePathEntries trims windows separators and quotes`() {
        val path = """C:\bin; "C:\Program Files\bin";;C:\tools  """

        val entries = parsePathEntries(path, ';')

        assertEquals(listOf("C:\\bin", "C:\\Program Files\\bin", "C:\\tools"), entries)
    }

    @Test
    fun `parsePathEntries handles mac separators`() {
        val path = """/usr/bin:/bin:"/Applications/Tools":/opt/homebrew/bin"""

        val entries = parsePathEntries(path, ':')

        assertEquals(
            listOf("/usr/bin", "/bin", "/Applications/Tools", "/opt/homebrew/bin"),
            entries,
        )
    }
}
