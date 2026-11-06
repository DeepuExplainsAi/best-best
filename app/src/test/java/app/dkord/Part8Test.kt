package app.dkord

import app.dkord.data.auth.CookieSource
import org.junit.Assert.assertNull
import org.junit.Test

class Part8Test {
    @Test fun noCookiesAreEverProvided() {
        val source: CookieSource = CookieSource { null }
        assertNull(source.cookies())
    }
}
