# What is left to publish TV Launcher (your part)

Everything below needs your accounts or your decisions, so it could not be done for you. In order:

## 1. Keep the upload key safe (5 minutes)

`~/android-toolchain/keys/tvlauncher-upload-key-backup.zip` holds the keystore and its passwords. Put it in your password manager (as an attachment) or an encrypted cloud folder. If it is ever lost, the published app can never be updated again.

## 2. GitHub — done

The code is public at https://github.com/schwerko/tv-launcher (MIT licence). The push token is stored in the macOS keychain, so later pushes are just `git push` from the project folder. The privacy-policy URL for Play is https://github.com/schwerko/tv-launcher/blob/main/PRIVACY.md.

## 3. Google Play developer account (15 minutes + a few days' wait)

play.google.com/console → register (one-time fee, identity verification; a personal account takes a few days to be approved).

## 4. Create the app and fill in the console (about an hour)

Create app → name **TV Launcher**, app (not game), free. Then work through, pasting from `PLAY_LISTING.md`:

- **Grow › Store presence › Main store listing**: short and full description, 512 icon, 1024×500 feature graphic (`store/`).
- **Release › Advanced settings › Form factors**: add **Android TV**; upload `store/tv_banner_1280x720.png` and the six `store/screenshot_*.png` under the TV listing.
- **Monitor and improve › Policy and programs › App content**: privacy policy URL (step 2), ads = no, app access = "all functionality available without special access" (the reviewer can use the app without keys; Ask and Cielo just show a setup dialog), content rating questionnaire, target audience 13+, news app = no, data safety (the table in `PLAY_LISTING.md`), government/financial/health = no.
- **Test and release › Testing › Closed testing**: create a track, upload `app/build/outputs/bundle/release/app-release.aab`, add an email list of testers.

Play may ask you to declare `RECORD_AUDIO`; the wording is in `PLAY_LISTING.md`. It should not ask about package visibility, because the app no longer requests `QUERY_ALL_PACKAGES`.

## 5. The 14-day closed test (Play's rule for personal accounts)

Twelve testers must opt in and stay opted in for 14 days before you can apply for production. Friends with any Android TV / Google TV box qualify; they install from the test link. After 14 days: Dashboard → "Apply for production access", answer the questionnaire, wait for approval, then promote the same bundle to Production.

## 6. Before each later upload

Bump `versionCode` (and `versionName`) in `app/build.gradle.kts`, then `./gradlew bundleRelease` with the environment variables from the README. Commit the change.

## 7. Optional

- Test the Unsplash background with a real access key (unsplash.com/developers → New application → Access Key, paste under Settings › Background › Unsplash). That code path has not been exercised with a key.
- Try the app on another TV box or an Android TV emulator image at 720p; the layout was verified on the Streamer at four densities but not on a second device.
- When your own box moves to the Play version: uninstall the sideloaded copy first, re-enter keys, and generate a new Cielo key (the old one has already authenticated once). See README "Moving a sideloaded box to the Play build".
