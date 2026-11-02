# What is left to publish TV Launcher (your part)

Everything below needs your accounts or your decisions, so it could not be done for you. In order:

## 1. Keep the upload key safe (5 minutes)

`~/android-toolchain/keys/tvlauncher-upload-key-backup.zip` holds the keystore and its passwords. Put it in your password manager (as an attachment) or an encrypted cloud folder. If it is ever lost, the published app can never be updated again.

## 2. GitHub — done

The code is public at https://github.com/schwerko/tv-launcher (MIT licence). The push token is stored in the macOS keychain, so later pushes are just `git push` from the project folder. The privacy-policy URL for Play is https://github.com/schwerko/tv-launcher/blob/main/PRIVACY.md.

## 3. Google Play developer account — done

Developer account "Schwerko" is approved. The app exists in the console as **TV Launcher Azure** (`com.azura.tvlauncher`, paid). Console link: https://play.google.com/console/u/0/developers/8852856123804392108/app/4975636464627548772/app-dashboard

## 4. Console set-up — dashboard complete (1 Nov 2026)

Done: store listing with all assets, Android TV form factor (opted in), every App content declaration including the IARC content rating, category Tools, contact details, payments profile, price USD 0.99 in 177 countries, closed-testing track "Alpha" with the 1.1 (2) bundle as a draft release and 173 countries (Rest of World, China, Cuba, Iran and Sudan are excluded: paid apps cannot be sold there).

## 5. Left for you

1. **Testers**: Closed testing → Alpha → Testers → Create email list: name it, paste 12 or more Gmail addresses (comma-separated), Save. Then tick the list and Save the Testers tab. Copy the opt-in link it shows and send it to the testers.
2. **Send for review**: Publishing overview → "Send app for review". Play review usually takes a few days for a first submission.
3. **Testers must opt in and stay opted in for 14 days** before you can apply for production access (Dashboard → "Apply for access to production").
4. **15% service fee**: the console notification "It's time to enroll for the 15% service fee" is Google's reduced-fee tier for the first USD 1M a year. Enrol under Manage account → Monetization setup; it halves Google's cut.

## 6. Before each later upload

Bump `versionCode` (and `versionName`) in `app/build.gradle.kts`, then `./gradlew bundleRelease` with the environment variables from the README. Commit the change.

## 7. Optional

- Test the Unsplash background with a real access key (unsplash.com/developers → New application → Access Key, paste under Settings › Background › Unsplash). That code path has not been exercised with a key.
- Try the app on another TV box or an Android TV emulator image at 720p; the layout was verified on the Streamer at four densities but not on a second device.
- When your own box moves to the Play version: uninstall the sideloaded copy first, re-enter keys, and generate a new Cielo key (the old one has already authenticated once). See README "Moving a sideloaded box to the Play build".
