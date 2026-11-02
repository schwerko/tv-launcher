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
  network or location is sent anywhere. Today's sunrise and sunset for that city are shown
  under the conditions.
- Calendar: paste one or more private ICS feed URLs, separated by spaces (Google Calendar:
  Settings → the calendar → "Secret address in iCal format", one per calendar; Outlook:
  Settings → Calendar → Shared calendars → Publish → ICS link). Shows one card per event for the
  coming 7 days in a row that scrolls sideways, merged across all feeds, including simple
  recurring ones; press a card for the time span, location and description. Outlook's Windows time-zone names are translated.
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
- Now playing: while Spotify, YouTube or any other app is playing or paused, a card shows the
  track, artist and album art with previous / play-pause / next buttons; press the title to
  jump into the app. It reads the device's media sessions, which Android only allows for apps
  with "notification access" (notification contents are never read). Phones and tablets have
  the switch under Settings › Apps › Special app access › Notification access (the launcher's
  Settings › "Now playing card" opens it); Google TV hides that screen, so grant it once:

  ```bash
  adb shell cmd notification allow_listener com.azura.tvlauncher/.MediaListener
  ```

- News, watched rather than read: Settings › "News (opens in YouTube)" offers a few
  well-known sources (BBC, NPR, The Guardian, The New York Times, Al Jazeera, ESPN, Ars
  Technica) to tick, or any RSS/Atom URL. Their headlines fill a row that scrolls sideways;
  pressing one opens a YouTube search for that story, so the TV plays the coverage instead
  of showing an article. A YouTube channel link works as a source too (its latest videos
  appear with thumbnails and play directly), but YouTube's channel feeds answer only
  intermittently, so the last good result is cached and a channel with nothing cached gets
  a single card that opens the channel. Refreshes every 30 minutes.
- What's on tonight: once a day the Ask assistant (whichever of Gemini or Claude is configured)
  writes three short lines, one live event or game, one new streaming pick and one classic,
  shown on a "Tonight" card. Press it for the full lines, "▶" buttons that open the titles in
  your streaming apps, "Ask again" and "Ask more". With Gemini (no live web) the first line is
  a best guess from memory; with Claude it comes from a web search. Turn the card off under
  Settings › "What's on tonight card". Settings › "Tonight interests" is a checklist (sports,
  films, series, documentaries, comedy, science fiction, …) folded into the daily question so
  the picks match your taste; changing it asks again. Costs one assistant question per day.
- Home Assistant: a row of chips for lights, switches, fans, covers, scenes, scripts and sensors
  from your own Home Assistant server. Settings › Home Assistant asks for the server address
  (e.g. `http://homeassistant.local:8123`), a long-lived access token (Home Assistant › your
  profile › Security › "Create token"; on a TV, type it with `adb shell input text '<token>'`
  while the field is focused) and then which entities to show. Pressing a chip toggles a
  light/switch/fan/cover or runs a scene/script; sensors just show their value. States refresh
  every minute. Plain `http://` to the server on your own network is allowed (the manifest
  permits cleartext traffic for this); everything else the app does is HTTPS. `tools/mock_ha.py`
  is a stand-in server for testing the card without a Home Assistant installation.
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
  Any titles the answer names become buttons (the TV edition of each streaming app is used
  on a TV, the phone edition on a phone or tablet). Disney+, Prime Video and YouTube open a search
  for the title directly. Netflix, HBO Max and Apple TV ignore search links, so for those the
  launcher asks the model for the title's own page and opens that (Claude looks it up on the
  web; Gemini answers from memory and may miss); if nothing is found it opens the app's search.
- Set up from your phone: Settings › "Set up from your phone…" shows a QR code, an address such
  as `http://192.168.1.50:8765/` and a 4-digit code. Scan or type the address on a phone (or any
  computer) on the same network and a single page lets you paste the long values: calendar
  links, news sources, background image links, the Gemini, Anthropic, Cielo and Unsplash keys,
  and the Home Assistant address and token. Fields left blank keep their current value; keys
  are never shown on the page. The page is served by the launcher itself, only on the TV's own
  LAN address, only while that screen is open, and saving needs the code (five wrong codes
  close it). Nothing goes through the internet.
- Refreshes automatically when apps are installed or removed.
- Themes (Settings › Theme): "Photo", the original look with background photos in colour, glass
  cards and a blue accent; and "Dark", the same background photos in black and white (plain black
  when the background is set to plain), grey glass cards, white text and white focus rings (the
  weather symbol is drawn in grey too). App banners, album art and the screensaver keep their
  colours.
- The header (clock, air conditioner, weather, Ask, Settings) stays fixed; the rows under it
  (Tonight, Home Assistant, calendar, news, the app grid) scroll beneath it as D-pad focus
  moves, and jump back to the top when focus returns to the header.

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
- `store/`: 512×512 icon, 1024×500 feature graphic, 1280×720 TV banner and eight 1920×1080
  screenshots taken on a Google TV Streamer (the calendar cards come from a demo feed and the
  Home Assistant chips from `tools/mock_ha.py`, not from a real home).

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
- Calendar feed: paste your ICS URL (typing on a TV is slow: use "Set up from your phone…"
  below, the remote app's keyboard, or `adb shell input text '...'` while the field is focused).
- News (opens in YouTube): tick well-known sources or paste RSS/Atom URLs or YouTube channel links.
- Tonight interests: what you like to watch, for the daily picks.
- Home Assistant: server address, access token, and which entities to show.
- What's on tonight card: on or off (needs a Gemini or Claude key).
- Theme: Photo or Dark.
- Background: plain dark, daily photos from Wikimedia Commons, Unsplash (asks for your access
  key), or your own image URLs (shown in black and white with the Dark theme).
- Ask uses / Gemini key / Claude key: pick the model and paste its key.
- Cielo AC: paste the API key (changing it forgets the old token and unit).
- Set up from your phone…: the QR-code page for pasting links and keys from a phone.
- Show hidden apps: unhides everything you've hidden.
- Android settings / Choose default launcher: shortcuts into system settings.

## Licence

MIT, see `LICENSE`. Photos shown by the app keep their own licences (Wikimedia Commons credits, Unsplash terms).
