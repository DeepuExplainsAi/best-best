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

class UpdaterTest {
    @org.junit.Test fun versionCompare() {
        val n = app.dkord.data.update.AppUpdater.Companion::isNewer
        org.junit.Assert.assertTrue(n("v0.10.0", "0.9.1"))
        org.junit.Assert.assertTrue(n("v0.3.1", "0.3.0"))
        org.junit.Assert.assertFalse(n("v0.3.0", "0.3.0"))
        org.junit.Assert.assertFalse(n("v0.2.0", "0.3.0"))
    }
}
