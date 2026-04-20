package io.qent.broxy.core.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UserPathResolverParsingTest {
    @Test
    fun extract_between_markers_reads_between_tokens() {
        val output = "prefix __START__value__END__ suffix"
        assertEquals("value", extractBetweenMarkers(output, "__START__", "__END__"))
        assertNull(extractBetweenMarkers(output, "__MISSING__", "__END__"))
        assertNull(extractBetweenMarkers(output, "__START__", "__MISSING__"))
    }

    @Test
    fun fallback_path_line_prefers_last_path_like_line() {
        val output = "noise\n/usr/bin:/bin\nlast-line\n"
        assertEquals("/usr/bin:/bin", fallbackPathLine(output, ':'))

        val outputNoSeparator = "first\nsecond\nthird\n"
        assertEquals("third", fallbackPathLine(outputNoSeparator, ':'))
    }
}
