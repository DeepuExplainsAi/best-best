package app.dkord

import app.dkord.ui.screens.choliwoodShelfTitle
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeLabelsTest {
    @Test fun english_upstream_headers_keep_choliwood_branding() {
        assertEquals("Popular Choliwood songs", choliwoodShelfTitle("Popular Bhojpuri songs", "Choliwood"))
        assertEquals("Choliwood hits", choliwoodShelfTitle("BHOJPURI hits", "Choliwood"))
    }

    @Test fun hindi_headers_also_use_the_exact_choliwood_spelling() {
        assertEquals("नए Choliwood गाने", choliwoodShelfTitle("नए भोजपुरी गाने", "Choliwood"))
    }

    @Test fun_existing_choliwood_and_other_headers_are_not_rewritten() {
        assertEquals("Choliwood - Popular", choliwoodShelfTitle("Choliwood - Popular", "Choliwood"))
        assertEquals("Bollywood Hits", choliwoodShelfTitle("Bollywood Hits", "Choliwood"))
    }
}
