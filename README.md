# TV Launcher

A clean home screen for a Google TV / Android TV box: every installed app in one grid, a clock,
the date, current weather, and an optional "up next" line from your calendar. No ads, no
recommendations, no accounts.

## Features

- All apps in a grid, sorted A–Z, using each app's TV banner (falls back to its icon).
- D-pad navigation with a highlight ring and scale-up on focus.
- Long-press (or the Menu button) on an app: pin to top, hide, app info, uninstall. Settings ›
  "Choose apps to show" is a checklist of every installed app for hiding many at once.
- Clock and date, respecting the box's 12/24-hour setting.
- Weather from Open-Meteo (free, no key) for a city you type in Settings; until you do, it uses
  the city of the box's time zone (e.g. "America/Phoenix" → Phoenix). Nothing about the box's
  network or location is sent anywhere.
- Calendar: paste one or more private ICS feed URLs, separated by spaces (Google Calendar:
  Settings → the calendar → "Secret address in iCal format", one per calendar; Outlook:
  Settings → Calendar → Shared calendars → Publish → ICS link). Shows one card per event for the
  next two days that have anything scheduled (within the coming 7 days), merged across all
  feeds, including simple recurring ones. Outlook's Windows time-zone names are translated.
- Background slideshow, cross-fading every 10 minutes, from one of three sources chosen under
  Settings › Background: Wikimedia Commons' pictures of the day (free licences, no key; the
  last three months' landscape-format photos), Unsplash (paste an access key from
  unsplash.com/developers; photos are credited "Photo by … on Unsplash" as its API terms
  require), or your own images (paste one or more image URLs). A dark overlay keeps text
  readable and the photo's author and licence are shown bottom right, as free licences
  require. (Version 1.0 used Bing's daily photo; that setting is migrated to Wikimedia Commons.)
- Screensaver: a full-screen slideshow from the same source as the background (Commons when the
  background is plain dark), with captions, credits, a clock, a slow zoom and a cross-fade
  every 20 seconds. Make it the box's screensaver with:

  ```bash
  adb shell settings put secure screensaver_components com.azura.tvlauncher/.SlideshowDream
  ```

  It starts after the box's idle timeout (10 minutes by default). To go back to Google's
  Ambient Mode: `adb shell settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop`
- Cielo mini-split control: a card next to the clock with power, temperature up/down, a cool/heat
  toggle (Cielo's API silently ignores "auto" on Breez units, so it isn't offered) and fan speed, plus room temperature and humidity. With several units, press the unit's name to
  switch between them; the launcher remembers the last one shown. Uses Cielo's official Connect API: get an API
  key at home.cielowigle.com (menu › Home Assistant) and enter it under Settings › Cielo AC.
  A key signs in only once, so the app keeps the token it receives; if you ever reinstall from
  scratch (or clear the app's data), revoke the key and generate a new one (3 per month).
- Ask: an "Ask" button in the header opens a panel where you speak or type a question.
  Voice uses the box's own speech recogniser from inside the launcher: press Speak and talk
  towards the remote without pressing its microphone button (that button is wired to Google
  Assistant, which this box reports as unavailable). The first use asks for microphone access.
  Two models are supported, chosen under Settings › "Ask uses":
  - Gemini (default, free): a Google AI Studio key (aistudio.google.com › API keys). The free
    tier has no Google Search grounding, so answers come from the model's own knowledge and
    very recent news may be missing. Uses `gemini-3.5-flash`, falling back to `gemini-3.8-flash`.
  - Claude (paid, live web search): an Anthropic key (console.anthropic.com), `claude-haiku-5-5`
    at low effort with up to two web searches; roughly 1–2 cents a question.
  Any titles the answer names become buttons. Disney+, Prime Video and YouTube open a search
  for the title directly. Netflix, HBO Max and Apple TV ignore search links, so for those the
  launcher asks the model for the title's own page and opens that (Claude looks it up on the
  web; Gemini answers from memory and may miss); if nothing is found it opens the app's search.
- Refreshes automatically when apps are installed or removed.

## Build

Open the `tv-launcher` folder in Android Studio and run **Build → Build APK(s)**, or from a
machine with a JDK 17 and the Android SDK (platform 36 and build-tools 36, which `sdkmanager`
installs on demand):

```bash
export JAVA_HOME=/path/to/jdk-17 ANDROID_HOME=/path/to/android-sdk
```

```bash
./gradlew assembleDebug
```

The APK ends up at `app/build/outputs/apk/debug/app-debug.apk`. The app targets Android 16
(API 36) and runs on Android 8.0+ (API 26).

### Release build (Play Store bundle)

Release builds are signed with an upload key that lives outside the repository. Once:

```bash
keytool -genkeypair -v -keystore ~/android-toolchain/keys/tvlauncher-upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
```

Then create `keystore.properties` next to this README (it is git-ignored, never commit it):

```properties
storeFile=/Users/you/android-toolchain/keys/tvlauncher-upload.jks
storePassword=…
keyAlias=upload
keyPassword=…
```

Build the signed bundle:

```bash
./gradlew bundleRelease
```

It lands at `app/build/outputs/bundle/release/app-release.aab`, ready for the Play Console
(see `PLAY_LISTING.md` for the listing text, declarations and store assets, and `PRIVACY.md`
for the privacy policy Play requires). Keep the keystore and its passwords backed up: Play
ties the app to this key, and a lost key means a new app listing. Without
`keystore.properties` the release build type falls back to the debug key so it still builds
locally, but Play will reject such a bundle. Minification is off on purpose (no ProGuard rules
have been written for this app). Bump `versionCode` in `app/build.gradle.kts` for every upload.

The app installs on Android TV, phones and tablets (on a phone or tablet it is a plain app
grid with the clock, weather and calendar, and can be set as the device's home app for a
kiosk-style front screen). `./gradlew bundleRelease -PtvOnly=true` builds a TV-only bundle
instead.

### Play Store files

- `PRIVACY.md`: the privacy policy (Play needs it at a public URL; a GitHub repository renders it).
- `PLAY_LISTING.md`: listing text, permission and data-safety declarations, content-rating
  answers and the adb instructions for users, plus the release checklist.
- `store/`: 512×512 icon, 1024×500 feature graphic, 1280×720 TV banner and six 1920×1080
  screenshots taken on a Google TV Streamer (the calendar cards show public holiday and
  moon-phase feeds, not a personal calendar).

### Moving a sideloaded box to the Play build

A build installed over adb is signed with the debug key (or with your upload key, which is
not the key Play signs with either), so the Play version cannot be installed over it: Android
refuses to update an app whose signature changes. Uninstall the sideloaded copy first
(`adb uninstall com.azura.tvlauncher`), install from Play, then re-enter the settings. API
keys are only stored on the box and are lost with the uninstall; for Cielo that means
revoking the old key and generating a new one, because a key authenticates only once. The
`pm disable-user` and `set-home-activity` steps below survive the switch.

## Install on the box

1. On the TV: Settings → System → About → tap "Android TV OS build" 7 times to enable
   developer options, then Settings → System → Developer options → USB debugging / Network
   debugging ON.
2. From your Mac (the box and Mac on the same network; find the box's IP under
   Settings → Network):

```bash
adb connect 192.168.1.50:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Make it the home screen

Google TV boxes usually hide the "default launcher" picker. Set it over adb:

```bash
adb shell cmd package set-home-activity com.azura.tvlauncher/.MainActivity
```

Press the Home button on the remote: the new launcher should appear. To go back to the stock
one:

```bash
adb shell cmd package set-home-activity com.google.android.apps.tv.launcherx/.GoogleTvLauncherActivity
```

(If that component name differs on your box, `adb shell cmd package resolve-activity -c android.intent.category.HOME -a android.intent.action.MAIN` lists candidates.)

On a Google TV Streamer (Android 14) the Home button still opens the stock launcher after
that: Google's launcher declares its HOME entry with priority 2, and Android picks the
highest-priority home before it ever looks at the preference you just set. A sideloaded
app can't outrank it, so the fix is to disable the stock launcher for the owner user:

```bash
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith
```

Both are needed: the setup app (`setupwraith`) contains a `RecoveryActivity` that also
registers as a home at priority 1, and its only job is to re-enable Google's launcher. With
only the first package disabled, Home lands on a blank recovery screen. Shell cannot disable
that one component on its own, so the whole setup package has to go. It is only used during
first-time setup and a factory reset re-enables everything.

Undo at any time with:

```bash
adb shell pm enable com.google.android.apps.tv.launcherx
adb shell pm enable com.google.android.tungsten.setupwraith
```

While it is disabled, the Google TV home, its recommendations and its "For you" rows are
gone; Play Store, Assistant, casting and all apps keep working.

## Settings

Focus the **Settings** button at the top right and press OK:

- Weather location: a city name, or blank for the city of the box's time zone.
- Temperature unit: toggles °C / °F.
- Calendar feed: paste your ICS URL (typing on a TV is slow; pasting works if your remote app
  supports it, or send text with `adb shell input text '...'` while the field is focused).
- Background: plain dark, daily photos from Wikimedia Commons, Unsplash (asks for your access
  key), or your own image URLs.
- Ask uses / Gemini key / Claude key: pick the model and paste its key.
- Cielo AC: paste the API key (changing it forgets the old token and unit).
- Show hidden apps: unhides everything you've hidden.
- Android settings / Choose default launcher: shortcuts into system settings.

## Licence

MIT, see `LICENSE`. Photos shown by the app keep their own licences (Wikimedia Commons credits, Unsplash terms).
