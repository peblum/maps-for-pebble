# Troubleshooting

The status section of the phone app shows every step of the pipeline: the last notification
text, the location fix, what the watch reported, and the result of the last navigation push
and the last frame. Read it first.

| Symptom | Cause | Fix |
|---------|-------|-----|
| "Pebble app: not installed" | No app on the phone implements PebbleKit Android 2. | Install the Core Devices Pebble app and pair the watch there. |
| "nav: companion not allowed by watchapp" | The watchapp on the watch is an older build without the companion package in its `companionApp` list. | Reinstall the current `maps-for-pebble.pbw`. |
| "nav: watchapp not open" | The watchapp is closed and the message was not one that relaunches it. | Open Maps Navigation on the watch, or wait for the next instruction, which relaunches it. |
| "nav: no watch connected" | The Pebble app has no connected watch. | Check the Pebble app. |
| "Waiting for GPS" on the watch, with advice under it | The phone knows why it has no fix: location is switched off, only approximate location was granted, or location is allowed only while the app is in use, so the service started from the background gets no fixes. | Do what the watch says. Precise location and "all the time" are both in the companion's setup section. |
| "Waiting for GPS" on the watch, with nothing under it | Everything is granted and the phone is still waiting for its first fix, usually indoors. | Step outside or wait. The location line in the status section shows the first fix. |
| "No map data" on the watch | No tiles cached for this area and no connection, or the tile server unreachable. | Wait for connectivity. The status line shows how many tiles the last frame used. |
| Map does not react to swipes | The watch runs an older watchapp, or touch is disabled system-wide on the watch. | Reinstall `maps-for-pebble.pbw`. |
| Watch does not open when directions start | Notification access is not granted, or Google Maps' notification was not recognised. | Check the setup section, then the "Notification text" line in the status once navigating. |
| Turn marker in the wrong place | The search found a street of the right name at about the right distance, but not the one Google meant, or it took a turn a vehicle could not. | It is an estimate. The turn arrow and distance at the top are Google's and always right. |

## Getting logs

```
adb logcat -s Navigator TileStore
```

`Navigator` logs session starts and stops with the notification key, service failures and
frame errors. The Pebble app logs every packet to and from the watch under the
`PebbleProtocol` tags, which shows whether messages were acknowledged.

The companion writes the last rendered frame to its cache directory as `preview.png` and
`last-frame.wmf`. On a debug build they can be pulled with `run-as org.peblum.mapsforpebble`, and the
`.wmf` can be replayed into the emulator with `scripts/emu-send.py --frame`.
