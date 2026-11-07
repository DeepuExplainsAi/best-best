package app.dkord

import app.dkord.ui.NavigationDirection
import app.dkord.ui.navigationDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationMotionTest {
    @Test fun opening_the_first_or_nested_screen_moves_forward() {
        assertEquals(NavigationDirection.FORWARD, navigationDirection(0, 1))
        assertEquals(NavigationDirection.FORWARD, navigationDirection(1, 2))
    }

    @Test fun closing_a_nested_screen_or_returning_home_moves_back() {
        assertEquals(NavigationDirection.BACK, navigationDirection(2, 1))
        assertEquals(NavigationDirection.BACK, navigationDirection(1, 0))
    }

    @Test fun same_depth_screen_changes_use_a_fade() {
        assertEquals(NavigationDirection.FADE, navigationDirection(0, 0))
        assertEquals(NavigationDirection.FADE, navigationDirection(2, 2))
    }
}
