# Google Maps in RESQGO

The key lives in `local.properties` as `MAPS_API_KEY`, which git ignores:

```
MAPS_API_KEY=AIza...
```

`app/build.gradle.kts` feeds it to the manifest and to `BuildConfig`, so no key is ever committed.
With no key the app still runs; the map tab says it is missing.

## What the project's key could do when it was tested (18 Nov 2026)

| API | Works | Used for |
| --- | --- | --- |
| Places API — `places:searchText` | yes | the destination search box |
| Routes API — `computeRoutes`, `TWO_WHEELER` | yes | distance, time and the route line |
| Maps SDK for Android | **no** | drawing the map itself |
| Geocoding API (legacy) | no | not used |
| Directions API (legacy) | no | replaced by Routes |
| Static Maps, Map Tiles | no | not used |

So search and routing work, but the map draws blank. Logcat says why:

```
E Google Maps Android API: Authorization failure.
E Google Maps Android API: Ensure that the "Google Maps Android API v2" is enabled.
E Google Maps Android API: Android Application (<cert_fingerprint>;<package_name>):
    71:EE:31:F8:A5:06:40:60:D6:C9:E3:E3:77:0B:B5:87:DC:5E:D6:E2;com.resqgo.app
```

## To make the map draw

In the Google Cloud console, on the project that owns the key:

1. Enable **Maps SDK for Android**. Legacy Geocoding also asked for billing to be enabled, so the
   project may need a billing account even though Places and Routes answer without one.
2. If the key is restricted to Android apps, add this debug build:
   - package `com.resqgo.app`
   - SHA-1 `71:EE:31:F8:A5:06:40:60:D6:C9:E3:E3:77:0B:B5:87:DC:5E:D6:E2`

   That fingerprint belongs to this machine's debug keystore
   (`~/.android/debug.keystore`). Every machine has a different one, and a release build has its
   own, so each needs adding. Read one with:

   ```
   keytool -list -v -alias androiddebugkey -keystore ~/.android/debug.keystore -storepass android -keypass android
   ```

3. Restrict the key to just the APIs above. An unrestricted key that leaks can be used by anyone,
   and the bill lands on the project owner.

Nothing in the app needs changing: the map starts drawing as soon as the API is enabled.

## Why turn-by-turn still opens the Google Maps app

The Maps SDK draws a map; live turn-by-turn guidance needs the separately licensed Navigation SDK.
`Navigate` therefore fires a `google.navigation:` intent (`mode=l` is two-wheeler), which costs
nothing and uses the app riders already know, while RESQGO keeps watching for crashes behind it.
