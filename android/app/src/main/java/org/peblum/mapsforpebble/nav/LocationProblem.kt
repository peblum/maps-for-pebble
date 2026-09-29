package org.peblum.mapsforpebble.nav

enum class LocationProblem(
    val advice: List<String>,
) {
    LOCATION_OFF(listOf("Turn on location", "on the phone")),
    APPROXIMATE_ONLY(listOf("Allow precise location", "in Maps Navigation")),
    NOT_ALL_THE_TIME(listOf("Allow location all the time", "in Maps Navigation")),
    ;

    companion object {
        fun of(
            locationEnabled: Boolean,
            precise: Boolean,
            allTheTime: Boolean,
        ): LocationProblem? =
            when {
                !locationEnabled -> LOCATION_OFF
                !precise -> APPROXIMATE_ONLY
                !allTheTime -> NOT_ALL_THE_TIME
                else -> null
            }
    }
}
