package org.peblum.mapsforpebble

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.peblum.mapsforpebble.map.AutoZoom
import org.peblum.mapsforpebble.map.FrameEncoder
import org.peblum.mapsforpebble.map.MapRenderer
import org.peblum.mapsforpebble.map.Preview
import org.peblum.mapsforpebble.map.RoutePreview
import org.peblum.mapsforpebble.map.TileData
import org.peblum.mapsforpebble.map.TileStore
import org.peblum.mapsforpebble.map.WebMercator
import org.peblum.mapsforpebble.nav.GoogleMapsNotification
import org.peblum.mapsforpebble.nav.LocationProblem
import org.peblum.mapsforpebble.nav.Maneuver
import org.peblum.mapsforpebble.nav.MorseCue
import org.peblum.mapsforpebble.nav.NavParser
import org.peblum.mapsforpebble.nav.NavState
import org.peblum.mapsforpebble.nav.SunAltitude
import org.peblum.mapsforpebble.pebble.Protocol
import org.peblum.mapsforpebble.pebble.SendResult
import org.peblum.mapsforpebble.pebble.WatchLink
import org.peblum.mapsforpebble.service.NavigationService
import java.io.File
import java.util.Locale

object Navigator {
    data class Status(
        val navState: NavState = NavState.STOPPED,
        val rawLines: List<String> = emptyList(),
        val listenerConnected: Boolean = false,
        val hasLocation: Boolean = false,
        val locationText: String = "",
        val watchText: String = "",
        val navSend: String = "",
        val frameText: String = "",
        val serviceText: String = "",
        val previewVersion: Int = 0,
    )

    private enum class ServiceState { STOPPED, STARTING, RUNNING }

    private sealed class Event {
        class Posted(
            val sbn: StatusBarNotification,
        ) : Event()

        class Removed(
            val key: String,
        ) : Event()

        object ServiceStarted : Event()

        object ServiceStopped : Event()

        class ServiceFailed(
            val reason: String,
        ) : Event()
    }

    private const val TAG = "Navigator"
    private const val MIN_FRAME_INTERVAL_MS = 1500L
    private const val ZOOM_FRAME_INTERVAL_MS = 250L
    private const val WATCH_LAUNCH_DELAY_MS = 1200L
    private const val STOP_LINGER_MS = 2000L
    private const val BEARING_MIN_SPEED_MPS = 0.8f
    private const val USER_AGENT = "MapsForPebble/1.0 (+https://github.com/peblum/maps-for-pebble)"
    private const val DEMO_LAT = 50.8467
    private const val DEMO_LON = 4.3525
    private const val MAX_TILES = 9
    private const val IMMINENT_TURN_METERS = 40.0
    private const val DEMO_STREET = "Rue des Bouchers"
    private const val DEMO_STEP_MS = 5000L
    private val DEMO_DISTANCES = intArrayOf(300, 150, 80, 40)

    private lateinit var app: Application
    private lateinit var link: WatchLink
    private lateinit var tiles: TileStore
    lateinit var preferences: Preferences
        private set
    private val renderer = MapRenderer()

    @Volatile
    private var dark = false

    private val handler = CoroutineExceptionHandler { _, e -> Log.e(TAG, "unhandled", e) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + handler)
    private val frameRequests = Channel<Unit>(Channel.CONFLATED)
    private val events = Channel<Event>(Channel.UNLIMITED)
    private val session = Mutex()
    private val navMutex = Mutex()

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    private var sessionId = 0

    @Volatile private var navState = NavState.STOPPED

    @Volatile private var arrow: ByteArray? = null

    @Volatile private var navKey: String? = null

    @Volatile private var location: Location? = null

    @Volatile private var bearing = 0.0

    @Volatile private var zoomOverride: Double? = null

    @Volatile private var demoMode = false

    @Volatile private var zoomFramePending = false
    private var imminentCueSent = false

    @Volatile private var serviceState = ServiceState.STOPPED

    @Volatile private var stopPending = false

    @Volatile private var fallbackListener: LocationListener? = null
    private var lastFrameEnd = 0L

    val previewFile: File get() = File(app.cacheDir, "preview.png")
    val frameFile: File get() = File(app.cacheDir, "last-frame.wmf")

    fun init(application: Application) {
        app = application
        preferences = Preferences(app)
        link = WatchLink(app)
        tiles = TileStore(File(app.cacheDir, "tiles"), USER_AGENT, scope) { requestFrame() }
        scope.launch { frameLoop() }
        scope.launch { eventLoop() }
    }

    fun onListenerConnected() = update { copy(listenerConnected = true) }

    fun onMapsNotification(sbn: StatusBarNotification) {
        events.trySend(Event.Posted(sbn))
    }

    fun onMapsNotificationRemoved(key: String) {
        events.trySend(Event.Removed(key))
    }

    fun onServiceStarted() {
        events.trySend(Event.ServiceStarted)
    }

    fun onServiceStopped() {
        events.trySend(Event.ServiceStopped)
    }

    fun onServiceFailed(reason: String) {
        Log.w(TAG, "location service failed: $reason")
        events.trySend(Event.ServiceFailed(reason))
    }

    private suspend fun eventLoop() {
        var carried: Event? = null
        while (true) {
            var event = carried ?: events.receive()
            carried = null
            if (event is Event.Posted) {
                var latest: Event.Posted = event
                while (true) {
                    val next = events.tryReceive().getOrNull() ?: break
                    if (next is Event.Posted && next.sbn.key == latest.sbn.key) {
                        latest = next
                    } else {
                        carried = next
                        break
                    }
                }
                event = latest
            }
            try {
                session.withLock { handleEvent(event) }
            } catch (e: Exception) {
                Log.e(TAG, "event failed", e)
            }
        }
    }

    private suspend fun handleEvent(event: Event) {
        when (event) {
            is Event.Posted -> {
                handleNotification(event.sbn)
            }

            is Event.Removed -> {
                if (event.key == navKey) stopSession("Google Maps notification removed")
            }

            Event.ServiceStarted -> {
                serviceState = ServiceState.RUNNING
                if (stopPending) {
                    stopPending = false
                    stopLocationService()
                } else {
                    update { copy(serviceText = "location service running" + backgroundLocationHint()) }
                }
            }

            Event.ServiceStopped -> {
                if (serviceState == ServiceState.RUNNING) {
                    serviceState = ServiceState.STOPPED
                    update { copy(serviceText = "location service stopped") }
                }
            }

            is Event.ServiceFailed -> {
                serviceState = ServiceState.STOPPED
                if (stopPending) {
                    stopPending = false
                } else {
                    update { copy(serviceText = "location service failed: ${event.reason}") }
                    startFallbackLocation()
                }
            }
        }
    }

    fun onLocation(newLocation: Location) {
        location = newLocation
        val wasDark = dark
        val fixTime = if (newLocation.time > 0L) newLocation.time else System.currentTimeMillis()
        dark = SunAltitude.isDark(newLocation.latitude, newLocation.longitude, fixTime)
        if (dark != wasDark) {
            scope.launch { if (navState.active) pushNav(relaunch = false) }
        }
        if (newLocation.hasBearing() && newLocation.hasSpeed() && newLocation.speed >= BEARING_MIN_SPEED_MPS) {
            bearing = newLocation.bearing.toDouble()
        }
        update {
            copy(
                hasLocation = true,
                locationText =
                    String.format(
                        Locale.US,
                        "%.5f, %.5f  ±%.0f m  %.1f m/s  heading %.0f°%s",
                        newLocation.latitude,
                        newLocation.longitude,
                        newLocation.accuracy,
                        newLocation.speed,
                        bearing,
                        if (dark) "  dark" else "",
                    ),
            )
        }
        requestFrame()
    }

    fun onWatchHello(
        inboxMax: Int?,
        width: Int?,
        height: Int?,
    ) {
        inboxMax?.takeIf { it > 0 }?.let { link.inboxMax = it }
        width?.takeIf { it > 0 }?.let { link.mapWidth = it }
        height?.takeIf { it > 0 }?.let { link.mapHeight = it }
        update { copy(watchText = "watch: inbox ${link.inboxMax} B, map ${link.mapWidth}x${link.mapHeight}, chunk ${link.chunkSize} B") }
        scope.launch { if (navState.active) pushNav(relaunch = false) }
        requestFrame()
    }

    fun onWatchAppOpened() {
        scope.launch { if (navState.active) pushNav(relaunch = false) }
        requestFrame()
    }

    fun onWatchAppClosed() = update { copy(navSend = "watchapp closed") }

    fun onPreferencesChanged() {
        scope.launch { if (navState.active) pushNav(relaunch = false) }
    }

    fun onZoomLevel(level: Int) {
        if (!navState.active || level < AutoZoom.MIN * Protocol.ZOOM_SCALE) return
        zoomOverride = (level.toDouble() / Protocol.ZOOM_SCALE).coerceIn(AutoZoom.MIN, AutoZoom.MAX)
        zoomFramePending = true
        requestFrame()
    }

    fun sendDemo() {
        scope.launch {
            val demoSession = session.withLock { startDemo() }
            for ((step, meters) in DEMO_DISTANCES.withIndex()) {
                val stillRunning =
                    session.withLock {
                        if (sessionId != demoSession) return@withLock false
                        setDemoState(meters)
                        pushNav(relaunch = false, cue = hapticCue(navState, newInstruction = step == 0))
                        requestFrame()
                        true
                    }
                if (!stillRunning) return@launch
                delay(DEMO_STEP_MS)
            }
        }
    }

    fun stopDemo() {
        scope.launch { session.withLock { if (demoMode) stopSession("demo stopped") } }
    }

    private suspend fun startDemo(): Int {
        Log.i(TAG, "demo started")
        demoMode = true
        arrow = null
        zoomOverride = null
        dark = true
        sessionId++
        link.launchApp()
        delay(WATCH_LAUNCH_DELAY_MS)
        return sessionId
    }

    private fun setDemoState(meters: Int) {
        val demoState =
            NavState(
                active = true,
                maneuver = Maneuver.TURN_LEFT,
                distance = "$meters m",
                distanceMeters = meters.toDouble(),
                street = DEMO_STREET,
                instruction = "Turn left onto $DEMO_STREET",
                eta = "10:45",
                distRemain = "2.1 km",
                timeRemain = "14 min",
            )
        navState = demoState
        update { copy(navState = demoState, rawLines = listOf("demo")) }
    }

    private suspend fun handleNotification(sbn: StatusBarNotification) {
        val read = GoogleMapsNotification.read(app, sbn) ?: return
        val parsed = NavParser.parse(read.raw)
        val plausible = sbn.id == 1 || parsed.distance.isNotEmpty() || read.arrow != null
        if (!plausible) return
        val previous = navState
        val starting = !previous.active || demoMode
        demoMode = false
        navKey = sbn.key
        navState = parsed
        read.arrow?.let { arrow = it }
        update { copy(navState = parsed, rawLines = read.raw.lines) }
        if (starting) {
            Log.i(TAG, "navigation started from notification ${sbn.key}")
            sessionId++
            stopPending = false
            zoomOverride = null
            dark = false
        }
        if (serviceState == ServiceState.STOPPED && fallbackListener == null) startLocationService()
        val newInstruction = starting || !previous.sameInstruction(parsed)
        pushNav(relaunch = newInstruction, cue = hapticCue(parsed, newInstruction))
        requestFrame()
    }

    private fun hapticCue(
        state: NavState,
        newInstruction: Boolean,
    ): ByteArray? {
        val distance = state.distanceMeters
        val withinReach = distance != null && distance <= IMMINENT_TURN_METERS
        if (newInstruction) imminentCueSent = withinReach
        if (!preferences.hapticCues) return null
        val imminent = !newInstruction && !imminentCueSent && withinReach
        if (!newInstruction && !imminent) return null
        if (imminent) imminentCueSent = true
        return MorseCue.pattern(state.maneuver)
    }

    private suspend fun stopSession(reason: String) {
        Log.i(TAG, "navigation stopped: $reason")
        navKey = null
        demoMode = false
        navState = NavState.STOPPED
        arrow = null
        zoomOverride = null
        dark = false
        update { copy(navState = NavState.STOPPED, rawLines = emptyList()) }
        stopLocationService()
        stopFallbackLocation()
        val stoppedSession = ++sessionId
        link.sendStopped()
        scope.launch {
            delay(STOP_LINGER_MS)
            session.withLock { if (sessionId == stoppedSession) link.stopApp() }
        }
    }

    private fun startLocationService() {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            update { copy(serviceText = "location permission not granted, no map") }
            return
        }
        stopPending = false
        serviceState = ServiceState.STARTING
        try {
            if (!NavigationService.start(app)) onServiceFailed("service start rejected")
        } catch (e: Exception) {
            onServiceFailed(e.javaClass.simpleName)
        }
    }

    private fun stopLocationService() {
        when (serviceState) {
            ServiceState.RUNNING -> {
                serviceState = ServiceState.STOPPED
                NavigationService.stop(app)
                update { copy(serviceText = "location service stopped") }
            }

            ServiceState.STARTING -> {
                stopPending = true
            }

            ServiceState.STOPPED -> {}
        }
    }

    private fun backgroundLocationHint(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            ", background location not allowed so GPS may stay silent until Maps Navigation is opened"
        } else {
            ""
        }

    private fun locationProblem(): LocationProblem? =
        LocationProblem.of(
            locationEnabled = LocationManagerCompat.isLocationEnabled(app.getSystemService(Context.LOCATION_SERVICE) as LocationManager),
            precise = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION),
            allTheTime = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
        )

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED

    private suspend fun pushNav(
        relaunch: Boolean,
        cue: ByteArray? = null,
    ) = navMutex.withLock {
        val keepLit = preferences.keepBacklightOn && dark
        var result = link.sendNav(navState, arrow, cue, keepLit)
        if (result == SendResult.AppNotOpen && relaunch) {
            link.launchApp()
            delay(WATCH_LAUNCH_DELAY_MS)
            result = link.sendNav(navState, arrow, cue, keepLit)
        }
        update { copy(navSend = "nav: $result") }
    }

    private fun requestFrame() {
        frameRequests.trySend(Unit)
    }

    private suspend fun frameLoop() {
        for (request in frameRequests) {
            while (true) {
                val interval = if (zoomFramePending) ZOOM_FRAME_INTERVAL_MS else MIN_FRAME_INTERVAL_MS
                val wait = lastFrameEnd + interval - System.currentTimeMillis()
                if (wait <= 0 || withTimeoutOrNull(wait) { frameRequests.receive() } == null) break
            }
            zoomFramePending = false
            try {
                renderAndSend()
            } catch (e: Exception) {
                Log.e(TAG, "frame failed", e)
                update { copy(frameText = "frame failed: $e") }
            }
            lastFrameEnd = System.currentTimeMillis()
        }
    }

    private suspend fun renderAndSend() {
        val state = navState
        if (!state.active) return
        val fix = location
        val hasFix = fix != null || demoMode
        val lat = fix?.latitude ?: DEMO_LAT
        val lon = fix?.longitude ?: DEMO_LON
        val heading = if (fix != null) bearing else 0.0
        val width = link.mapWidth
        val height = link.mapHeight
        val zoom = zoomOverride ?: AutoZoom.choose(lat, height, state.distanceMeters, fix?.speed)

        val tileList = if (hasFix) withContext(Dispatchers.IO) { loadTiles(lat, lon, zoom, width, height) } else emptyList()
        val preview = if (hasFix) buildPreview(lat, lon, heading, state, tileList) else null
        val advice = if (hasFix) emptyList() else locationProblem()?.advice.orEmpty()
        val bitmap = renderer.render(MapRenderer.Spec(width, height, lat, lon, heading, zoom, tileList, preview, hasFix, advice))
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val data = FrameEncoder.encode(pixels, width, height)
        withContext(Dispatchers.IO) { saveDebugFrame(bitmap, width, height, data) }
        val started = System.currentTimeMillis()
        val result = link.sendFrame(width, height, zoom, data)
        val elapsed = System.currentTimeMillis() - started
        update {
            copy(
                frameText =
                    String.format(
                        Locale.US,
                        "frame %dx%d, %d B, zoom %.2f, %d tiles, turn %s, sent in %d ms: %s",
                        width,
                        height,
                        data.size,
                        zoom,
                        tileList.size,
                        if (preview?.hasTurn == true) (if (preview.onRoad) "on road" else "straight ahead") else "unknown",
                        elapsed,
                        result,
                    ),
                previewVersion = previewVersion + 1,
            )
        }
    }

    private fun buildPreview(
        lat: Double,
        lon: Double,
        heading: Double,
        state: NavState,
        tileList: List<TileData>,
    ): Preview =
        RoutePreview.build(
            WebMercator.toWorldX(lon),
            WebMercator.toWorldY(lat),
            heading,
            if (state.rerouting) null else state.distanceMeters,
            state.street,
            tileList,
            WebMercator.metersPerUnit(lat),
            state.maneuver.turnsLeft,
        )

    private fun loadTiles(
        lat: Double,
        lon: Double,
        zoom: Double,
        width: Int,
        height: Int,
    ): List<TileData> {
        val radiusUnits = MapRenderer.screenRadiusPixels(width, height) / WebMercator.pixelsPerUnit(zoom)
        val centerX = WebMercator.toWorldX(lon)
        val centerY = WebMercator.toWorldY(lat)
        val result = ArrayList<TileData>()
        for (x in WebMercator.tileOf(centerX - radiusUnits)..WebMercator.tileOf(centerX + radiusUnits)) {
            for (y in WebMercator.tileOf(centerY - radiusUnits)..WebMercator.tileOf(centerY + radiusUnits)) {
                if (result.size >= MAX_TILES) return result
                tiles.get(x, y)?.let { result.add(it) }
            }
        }
        return result
    }

    private fun saveDebugFrame(
        bitmap: Bitmap,
        width: Int,
        height: Int,
        data: ByteArray,
    ) {
        runCatching {
            writeAtomically(previewFile) { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream) }
            writeAtomically(frameFile) { stream -> stream.write(FrameEncoder.toWmf(width, height, data)) }
        }
    }

    private fun writeAtomically(
        target: File,
        write: (java.io.OutputStream) -> Unit,
    ) {
        val temp = File(target.path + ".tmp")
        temp.outputStream().use(write)
        temp.renameTo(target)
    }

    private fun startFallbackLocation() {
        if (fallbackListener != null) return
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return
        val manager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener =
            object : LocationListener {
                override fun onLocationChanged(location: Location) = onLocation(location)

                override fun onProviderEnabled(provider: String) {}

                override fun onProviderDisabled(provider: String) {}

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(
                    provider: String?,
                    status: Int,
                    extras: Bundle?,
                ) {}
            }
        try {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, app.mainLooper)
            fallbackListener = listener
            update { copy(serviceText = serviceText + " (using in-process location updates)" + backgroundLocationHint()) }
        } catch (e: Exception) {
            Log.w(TAG, "fallback location failed: $e")
        }
    }

    private fun stopFallbackLocation() {
        val listener = fallbackListener ?: return
        fallbackListener = null
        val manager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        runCatching { manager.removeUpdates(listener) }
    }

    private inline fun update(block: Status.() -> Status) {
        _status.update { it.block() }
    }
}
