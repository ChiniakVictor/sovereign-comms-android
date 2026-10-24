# Sovereign Comms for Android

Source code for Sovereign Comms for Android 2.5, made by Victor.

Off-grid position sharing, messaging, push-to-talk and maps over Meshtastic LoRa radios, with optional internet sharing (MQTT) and calls (WebRTC) when there is service. Offline USGS topo and world maps, trails, easements and pins.

## Download

Download and install the app for free from [kodiakking.com](https://kodiakking.com).

## Build

Requirements: JDK 17 or 21, Android SDK (compileSdk 35), or Android Studio.

```
./gradlew assembleDebug        # debug APK: app/build/outputs/apk/debug/
./gradlew assembleRelease      # release APK: app/build/outputs/apk/release/
```

Windows: use `gradlew.bat` instead of `./gradlew`. Create a `local.properties` with `sdk.dir=...` if `ANDROID_HOME` is not set (Android Studio does this for you).

### Signing (optional)

Release signing reads `keystore/keystore.properties`, which is not in the repository. Copy `keystore/keystore.properties.example` to `keystore/keystore.properties`, put your own keystore in `keystore/`, and fill in the values. Without it the release build is unsigned.

## Notes

**Relay (TURN) server and group passcode:** no server address, username, password or group passcode is included. Each user enters their own relay server details and group passcode in the app's Internet settings at runtime; nothing secret is needed at build time.

`data/kodiak/` holds the scripts and source notes used to build the Kodiak map layers; the large raw GIS downloads are not included (re-run the fetch scripts to recreate them).

## License

This project is licensed under the GNU General Public License v3.0; see `LICENSE`.
