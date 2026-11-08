# TV Launcher privacy policy

_Last updated: 7 November 2026. Applies to the Android TV app "TV Launcher" (package `com.azura.tvlauncher`)._

## The short version

- The developer collects nothing. There is no account, no analytics, no crash reporting, no advertising and no server of ours. The app never contacts the developer.
- The app only talks to services **you** switch on in its Settings, and only sends them what they need to work. If you leave a feature off, that service is never contacted.
- Everything you enter (city, calendar links, API keys) stays in the app's private storage on the TV.

## What the app sends, and to whom

Each feature below is optional. Nothing is sent until you set it up, except the weather, which is on by default and uses a city name only.

| Feature | Service contacted | What is sent | Their policy |
|---|---|---|---|
| Weather, sunrise and sunset | Open-Meteo (`api.open-meteo.com`, `geocoding-api.open-meteo.com`) | The city name you typed, or, if you typed none, the city of the TV's time zone (for example "Phoenix"). No IP-based or GPS location is used. | https://open-meteo.com/en/terms |
| Calendar cards | The host of each calendar (ICS) address you pasted, e.g. Google Calendar or Outlook | A request for that address. The events it returns are read on the TV and go nowhere else. | Your calendar provider's policy |
| News | The publisher of each RSS/Atom feed you ticked or pasted (e.g. `feeds.bbci.co.uk`, `feeds.npr.org`), or YouTube (`www.youtube.com`, `i.ytimg.com`) for a channel you added | A request for the feed (and thumbnails for a YouTube channel). Pressing a headline hands the headline text to the YouTube app as a search; pressing a video hands its id to the YouTube app. | That publisher's policy; https://policies.google.com/privacy |
| What's on tonight | The assistant you chose for Ask (Google Gemini or Anthropic Claude, as below) | Once a day: today's date, the weather city and the interests you ticked (e.g. "Sports, Comedy"), asking for three viewing suggestions. | As for Ask |
| Home Assistant row | Your own Home Assistant server, at the address you typed (usually on your home network) | The access token you created there, requests for entity states, and the toggle/run commands you press. The developer has no access to it. | Your own server |
| Cielo air-conditioner card | Cielo Connect API (`api.smartcielo.com`) | The Cielo API key you entered, the access token Cielo issues for it, and the commands you press (power, temperature, mode, fan). | https://www.cielowigle.com/privacy-policy |
| Mitsubishi Comfort air conditioners (setup) | Mitsubishi Electric Kumo Cloud (`app-prod.kumocloud.com`, `socket-prod.kumocloud.com`) | Only when you run setup: your Mitsubishi Comfort email and password, to sign in and read each indoor unit's local-control key. The password is used for that sign-in and not stored. | https://www.mitsubishicomfort.com/privacy-policy |
| Mitsubishi Comfort air conditioners (control) | The Wi-Fi adapters in your own indoor units, on your home network | Status requests and the commands you press (power, temperature, mode, fan), signed with each unit's key. Nothing goes through the internet. | — |
| Ask (Gemini) | Google Generative Language API (`generativelanguage.googleapis.com`) | Your Gemini API key and the question you typed or spoke, plus the weather city as context. | https://policies.google.com/privacy |
| Ask (Claude) | Anthropic API (`api.anthropic.com`) | Your Anthropic API key and the question, plus the weather city. Anthropic may run a web search to answer. | https://www.anthropic.com/privacy |
| Background photos (default) and screensaver | Wikimedia Commons (`commons.wikimedia.org`, `upload.wikimedia.org`) | A request for recent "Picture of the day" images. Nothing about you. | https://foundation.wikimedia.org/wiki/Policy:Privacy_policy |
| Background photos (Unsplash, optional) | Unsplash API (`api.unsplash.com`, `images.unsplash.com`) | Your Unsplash access key and a request for landscape photos. | https://unsplash.com/privacy |
| Background photos (your own URLs) | Whatever hosts you pasted | A request for each image. | That host's policy |

All connections use HTTPS, except to a Home Assistant server whose address you typed with `http://` (servers on a home network usually have no certificate); that traffic stays on your network.

## Microphone

The Ask panel has a **Speak** button. Only while you have pressed it does the app listen, through the TV's own speech-recognition service (on Google TV that is Google's speech service, covered by Google's privacy policy). The app receives the recognised text, not the audio, and sends that text to the assistant you chose. The app never records in the background and never stores audio. You can deny the microphone permission and type instead.

## What stays on the TV

Stored in the app's private storage, readable only by the app:

- the weather city and °C/°F choice;
- calendar feed addresses and news feed addresses;
- API keys for Gemini, Anthropic, Cielo and Unsplash, and the Cielo access token;
- the Home Assistant address, access token and chosen entities;
- the Mitsubishi Comfort account email, and each indoor unit's name, local-control key and network address (not the account password);
- the day's "What's on tonight" lines and the interests you ticked;
- a cache of news thumbnails and the last headlines of each source;
- which apps you hid or pinned, and the last air-conditioner unit shown;
- a cache of downloaded background images.

Android app backup is turned off for this app, so these values are not copied to a cloud backup. Uninstalling the app, or clearing its data in Android settings, deletes all of it. Revoke an API key at the service that issued it if you want to be sure it can no longer be used.

## Now playing card (notification access)

Android only lets an app list the device's active media sessions (what Spotify, YouTube and similar apps are playing) if that app holds the "notification access" permission. TV Launcher asks for it solely for the Now playing card: track title, artist, album art and the play, pause and skip buttons. The app never reads, stores or forwards the contents of notifications, and the permission is optional; without it the card simply stays hidden.

## Set up from your phone (local page)

Settings › "Set up from your phone…" starts a small web page served by the app itself, at the TV's own address on your home network (for example `http://192.168.1.50:8765/`), so links and keys can be pasted from a phone instead of typed with the remote. It runs only while that screen is open on the TV, accepts connections only from your local network, needs the 4-digit code shown on the TV to save anything, and never displays keys already stored. Values travel directly from the phone to the TV over your own network; no internet service is involved. The developer cannot reach the page.

## Installed apps

As a home screen, the app asks Android for the apps that can be opened from a launcher so it can show them in its grid. That list is used only to draw the grid and is never transmitted.

## Children

The app is a general-purpose home screen and is not directed at children.

## Changes and contact

If this policy changes, the new version will be published at the same address and the date above updated. Questions: open an issue in the project's repository or use the developer contact shown on the Play Store listing.
