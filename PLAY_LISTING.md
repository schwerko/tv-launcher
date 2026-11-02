# Google Play listing material

Everything the Play Console asks for when the app is submitted, ready to paste. Assets are in
[`store/`](store/). The privacy policy is [`PRIVACY.md`](PRIVACY.md); its public URL for
**App content › Privacy policy** is https://github.com/schwerko/tv-launcher/blob/main/PRIVACY.md.

## Store listing

**App name** (30 max): `TV Launcher`

**Short description** (80 max):

> Calm Google TV home screen: your apps, clock, weather, calendar, news and more.

**Full description** (4000 max):

> TV Launcher replaces the busy Google TV home with one calm screen: every installed app in a grid, the time and date, the weather, the next things on your calendar, and optional cards for what's on tonight, the news, what's playing and your smart home. Two looks: colour photos, or a Dark theme with the same photos in black and white. No recommendations, no ads, no accounts, no tracking.
>
> WHAT YOU GET
> • All apps in one grid, sorted A–Z, using each app's TV banner. Pin favourites to the top, hide the ones you never use.
> • Clock and date, in the TV's own 12/24-hour format.
> • Weather from Open-Meteo for any city you type (starts with the city of your time zone).
> • Calendar cards: paste the private ICS link of a Google, Outlook or iCloud calendar and see the next 7 days in a row you scroll through; press a card for the details. Several feeds are merged.
> • Background photos: Wikimedia Commons' picture of the day, your own Unsplash key, or your own image URLs. Plain dark if you prefer.
> • Two themes, under Settings > Theme: Photo (colour background photos, glass cards, blue accents) or Dark (the same photos in black and white, grey glass cards, white text).
> • Screensaver: a full-screen slideshow of those photos with a clock and captions.
> • Ask: press one button, speak or type a question, and get an answer from Google Gemini (free key) or Claude (paid key, with live web search). Titles the answer mentions open directly in Netflix, HBO Max, Apple TV, Disney+, Prime Video or YouTube.
> • Cielo mini-split air-conditioner card: power, temperature, mode and fan, with the room's temperature and humidity.
> • Now playing: what Spotify, YouTube or any app is playing, with play/pause and skip.
> • Sunrise and sunset for your weather city.
> • News you watch, not read: headlines from sources you choose (BBC, NPR, ESPN and more, or any RSS address); press one and YouTube plays the coverage.
> • What's on tonight: three daily picks written by the assistant around the interests you tick, opening straight into your streaming apps.
> • Home Assistant: your lights, switches, scenes and sensors as chips on the home screen, from your own server.
>
> SET UP FROM YOUR PHONE OR PC
> Nothing long is typed with the remote. Settings › "Set up from your phone" shows a QR code and an address. Scan it with your phone, or type the address into any browser on the same Wi-Fi, and one page lets you paste calendar links, news sources and API keys straight to the TV. Saving needs the 4-digit code shown on the TV; the page exists only while that screen is open, only on your home network, and keys already stored are never shown.
>
> BRING YOUR OWN KEYS
> Weather, photos and the launcher itself need no account. The assistant, Unsplash and Cielo features use API keys you create with those services and paste in from your phone. Keys stay on the TV. The developer runs no server and collects nothing. See the privacy policy for exactly which service receives what.
>
> MAKING IT YOUR HOME SCREEN
> Google TV has no "choose launcher" setting, so making TV Launcher the home screen takes a one-time step from a computer with adb (Android's debugging tool). The instructions are in the app's Settings under "Choose default launcher" and on the support page. Until you do that, TV Launcher works as a normal app you open from the Google TV home.
>
> Made for Android TV and Google TV (Android 8.0 or later) and the remote's D-pad. It also installs on phones and tablets, where it works as a simple kiosk-style front screen.

**Category**: Tools. **Tags**: launcher, home screen, TV.

**Contact email**: the address in your Play developer account. **Support page**: the project's README.

## Graphics

| Asset | Requirement | File |
|---|---|---|
| App icon | 512×512 PNG | `store/icon_512.png` |
| Feature graphic | 1024×500 PNG | `store/feature_graphic_1024x500.png` |
| TV banner | 1280×720 PNG (required for Android TV listings) | `store/tv_banner_1280x720.png` |
| TV screenshots | 1920×1080, 16:9, at least one; up to eight | `store/screenshot_*.png` (home, Dark theme, Tonight, Ask, event details, Settings, screensaver, set up from your phone) |

## App content declarations

### Target audience
13 and over (or "18 and over"). The app is not designed for children.

### Ads
No, the app contains no ads.

### Permissions declaration (Play asks for these because of the manifest)

- **No `QUERY_ALL_PACKAGES`.** The app finds installed apps through a `<queries>` block (MAIN/LAUNCHER and MAIN/LEANBACK_LAUNCHER intents, plus the six streaming apps it deep-links into and the speech-recogniser intent), which is the mechanism Play prefers, so the "Package visibility" declaration form does not apply. If Play ever shows that form anyway, the answer is: *launcher app; installed apps are listed only to be opened from the grid and never transmitted.*
- **`RECORD_AUDIO`** – a runtime permission; no separate form, but the reviewer will look for it in the data-safety answers below. In-app use: *voice questions in the Ask panel; the microphone is used only while the user has pressed Speak, through the system speech recogniser.* The manifest marks the microphone as optional hardware (`uses-feature … microphone required=false`) so boxes without one can still install the app.
- `REQUEST_DELETE_PACKAGES`, `INTERNET`, `ACCESS_NETWORK_STATE` – normal permissions, nothing to declare.
- **Notification access** (`BIND_NOTIFICATION_LISTENER_SERVICE` on `MediaListener`) – not a form in the console, but reviewers check that the use is explained in the app and the policy. Wording: *used only to list active media sessions for the Now playing card; notification contents are never read, stored or transmitted; the feature is optional.*

### Data safety form

Everything below is sent to third-party services the user has chosen; nothing goes to the developer. Play counts data as "collected" when it leaves the device, so answer:

- *Does your app collect or share any of the required user data types?* **Yes** (because questions, a city name and API keys are sent to the services the user configures).
- *Is all of the user data collected by your app encrypted in transit?* **Yes** (HTTPS only).
- *Do you provide a way for users to request that their data is deleted?* **Yes** — the developer holds no data; the user deletes everything by clearing the app's data or uninstalling, and revokes keys at the issuing service. (If the form insists on a URL, link the "What stays on the TV" section of the privacy policy.)

Data types to declare (all **optional**, all **shared**, purpose **App functionality**, none **collected by the developer**, none used for advertising or analytics):

| Data type | Why | Shared with |
|---|---|---|
| Location › Approximate location | The city name the user types (or the time-zone city) is sent to Open-Meteo for weather and passed to the assistant as context. | Open-Meteo, Google (Gemini), Anthropic |
| App activity › Other user-generated content | The questions the user types or speaks in the Ask panel. | Google (Gemini), Anthropic |
| Personal info › Other info | API keys the user enters (Gemini, Anthropic, Cielo, Unsplash); each is sent only to the service that issued it. | Google, Anthropic, Cielo, Unsplash |
| App activity › Other actions | Air-conditioner commands (power, temperature, mode, fan). | Cielo |
| Personal info › Other info | The Home Assistant access token the user created, sent only to the user's own Home Assistant server at the address they typed. | The user's own server |
| App activity › Other actions | Home Assistant toggle / scene commands the user presses. | The user's own server |

The "Set up from your phone" page is served by the app on the local network only (no internet host), so it adds nothing to the form. Not declared because it never leaves the device: the installed-app list, calendar events and news headlines (downloaded, not uploaded), hidden/pinned apps, the °C/°F choice. The "What's on tonight" card sends only the date, the weather city and the ticked interests (e.g. "Sports") to the assistant (covered by the location and user-generated-content rows above). A pressed headline is handed to the YouTube app on the device as a search, not sent by this app. Audio is not declared: the app receives text from the system speech recogniser and never records or stores audio itself (say so in the "Voice or sound recordings" question if the reviewer asks).

### Government apps, financial features, health
No to all.

### News
No. (The headlines row shows feeds the user chooses, like a feed reader; the app is a launcher, not a news publisher. If a reviewer asks, that is the answer.)

### Content rating questionnaire
Category "Utility, Productivity, Communication, or Other". Answer no to the violence, sexuality, language, controlled-substance and gambling questions; **yes** to "does the app let users communicate or share user-generated content with a third party" only if the questionnaire counts sending a question to an AI service — if unsure, answer yes and explain. Expected result: Everyone / PEGI 3.

### Android TV form factor
Opt in under **Release › Advanced settings › Form factors › Android TV** and upload the TV banner and 16:9 screenshots. Play's TV review checks: a Leanback launcher entry (present), a 320×180 banner (`res/drawable/banner.xml`), no touchscreen requirement (declared), everything reachable with the D-pad (it is).

## Make it the home screen (support text for the listing)

Google TV has no launcher picker, so this is done once from a computer. Users need USB/network debugging on (Settings → System → About → press "Android TV OS build" seven times, then Settings → System → Developer options → USB debugging / Network debugging ON) and adb installed (Android Studio, or the "SDK Platform Tools" download).

```bash
adb connect <TV IP address>:5555
```

```bash
adb shell cmd package set-home-activity com.azura.tvlauncher/.MainActivity
```

On most Android TV boxes that is enough: press Home. On the Google TV Streamer and other Google TV devices the stock launcher is declared at a higher priority than any installed app, so it also has to be disabled for the owner user:

```bash
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
```

```bash
adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith
```

The second package is Google's setup app, which otherwise re-enables the stock launcher at every Home press. Play Store, Assistant, casting and all apps keep working. To go back:

```bash
adb shell pm enable com.google.android.apps.tv.launcherx
```

```bash
adb shell pm enable com.google.android.tungsten.setupwraith
```

```bash
adb shell cmd package set-home-activity com.google.android.apps.tv.launcherx/.GoogleTvLauncherActivity
```

Screensaver (optional): `adb shell settings put secure screensaver_components com.azura.tvlauncher/.SlideshowDream`, and `adb shell settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop` to restore Google's Ambient Mode.

## Release checklist

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Build the signed bundle: see README "Release build".
3. Play Console → Testing → Closed testing: upload `app-release.aab`, add testers (Play requires 12 testers for 14 days before a personal account can publish to production), fill in the forms above.
4. After the test period, apply for production access, then Production → Create release with the same bundle.
