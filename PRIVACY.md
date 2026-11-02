# TV Launcher privacy policy

_Last updated: 1 November 2026. Applies to the Android TV app "TV Launcher" (package `com.azura.tvlauncher`)._

## The short version

- The developer collects nothing. There is no account, no analytics, no crash reporting, no advertising and no server of ours. The app never contacts the developer.
- The app only talks to services **you** switch on in its Settings, and only sends them what they need to work. If you leave a feature off, that service is never contacted.
- Everything you enter (city, calendar links, API keys) stays in the app's private storage on the TV.

## What the app sends, and to whom

Each feature below is optional. Nothing is sent until you set it up, except the weather, which is on by default and uses a city name only.

| Feature | Service contacted | What is sent | Their policy |
|---|---|---|---|
| Weather | Open-Meteo (`api.open-meteo.com`, `geocoding-api.open-meteo.com`) | The city name you typed, or, if you typed none, the city of the TV's time zone (for example "Phoenix"). No IP-based or GPS location is used. | https://open-meteo.com/en/terms |
| Calendar cards | The host of each calendar (ICS) address you pasted, e.g. Google Calendar or Outlook | A request for that address. The events it returns are read on the TV and go nowhere else. | Your calendar provider's policy |
| Cielo air-conditioner card | Cielo Connect API (`api.smartcielo.com`) | The Cielo API key you entered, the access token Cielo issues for it, and the commands you press (power, temperature, mode, fan). | https://www.cielowigle.com/privacy-policy |
| Ask (Gemini) | Google Generative Language API (`generativelanguage.googleapis.com`) | Your Gemini API key and the question you typed or spoke, plus the weather city as context. | https://policies.google.com/privacy |
| Ask (Claude) | Anthropic API (`api.anthropic.com`) | Your Anthropic API key and the question, plus the weather city. Anthropic may run a web search to answer. | https://www.anthropic.com/privacy |
| Background photos (default) and screensaver | Wikimedia Commons (`commons.wikimedia.org`, `upload.wikimedia.org`) | A request for recent "Picture of the day" images. Nothing about you. | https://foundation.wikimedia.org/wiki/Policy:Privacy_policy |
| Background photos (Unsplash, optional) | Unsplash API (`api.unsplash.com`, `images.unsplash.com`) | Your Unsplash access key and a request for landscape photos. | https://unsplash.com/privacy |
| Background photos (your own URLs) | Whatever hosts you pasted | A request for each image. | That host's policy |

All connections use HTTPS.

## Microphone

The Ask panel has a **Speak** button. Only while you have pressed it does the app listen, through the TV's own speech-recognition service (on Google TV that is Google's speech service, covered by Google's privacy policy). The app receives the recognised text, not the audio, and sends that text to the assistant you chose. The app never records in the background and never stores audio. You can deny the microphone permission and type instead.

## What stays on the TV

Stored in the app's private storage, readable only by the app:

- the weather city and °C/°F choice;
- calendar feed addresses;
- API keys for Gemini, Anthropic, Cielo and Unsplash, and the Cielo access token;
- which apps you hid or pinned, and the last air-conditioner unit shown;
- a cache of downloaded background images.

Android app backup is turned off for this app, so these values are not copied to a cloud backup. Uninstalling the app, or clearing its data in Android settings, deletes all of it. Revoke an API key at the service that issued it if you want to be sure it can no longer be used.

## Now playing card (notification access)

Android only lets an app list the device's active media sessions (what Spotify, YouTube and similar apps are playing) if that app holds the "notification access" permission. TV Launcher asks for it solely for the Now playing card: track title, artist, album art and the play, pause and skip buttons. The app never reads, stores or forwards the contents of notifications, and the permission is optional; without it the card simply stays hidden.

## Installed apps

As a home screen, the app asks Android for the apps that can be opened from a launcher so it can show them in its grid. That list is used only to draw the grid and is never transmitted.

## Children

The app is a general-purpose home screen and is not directed at children.

## Changes and contact

If this policy changes, the new version will be published at the same address and the date above updated. Questions: open an issue in the project's repository or use the developer contact shown on the Play Store listing.
