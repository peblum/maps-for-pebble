package org.peblum.mapsforpebble.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocationProblemTest {
    @Test
    fun locationSwitchedOffWinsOverMissingPermissions() {
        assertEquals(LocationProblem.LOCATION_OFF, LocationProblem.of(locationEnabled = false, precise = false, allTheTime = false))
    }

    @Test
    fun approximateLocationCannotDrawTheMap() {
        assertEquals(LocationProblem.APPROXIMATE_ONLY, LocationProblem.of(locationEnabled = true, precise = false, allTheTime = true))
    }

    @Test
    fun whileInUseLocationStaysSilentBehindGoogleMaps() {
        assertEquals(LocationProblem.NOT_ALL_THE_TIME, LocationProblem.of(locationEnabled = true, precise = true, allTheTime = false))
    }

    @Test
    fun everythingGrantedMeansJustWaiting() {
        assertNull(LocationProblem.of(locationEnabled = true, precise = true, allTheTime = true))
    }
}
