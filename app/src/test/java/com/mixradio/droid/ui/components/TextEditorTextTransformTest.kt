// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0
package com.mixradio.droid.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class TextEditorTextTransformTest {
    @Test
    fun `removeEmptyLines removes whitespace-only lines`() {
        assertEquals("first\nlast", removeEmptyLines("first\n\n  \n\t\nlast"))
    }

    @Test
    fun `indentAllLines adds two spaces to every line`() {
        assertEquals("  first\n  second\n  ", indentAllLines("first\nsecond\n"))
    }

    @Test
    fun `removeAllLineBreaks removes LF CRLF and CR`() {
        assertEquals("firstsecondthirdfourth", removeAllLineBreaks("first\nsecond\r\nthird\rfourth"))
    }
}
