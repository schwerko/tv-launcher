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

The test track runs 1.1.1 (3); 1.1.2 (5) (app-drawer icon on phones/tablets, app label "TV Launcher Azure") was approved; 1.1.3 (6) (Settings › "Choose apps to show") is live for testers; 1.1.4 (7) (Ask opens the phone editions of Netflix, YouTube, Prime Video and Apple TV when those are what is installed) is in review; 1.1.5 (8) (7-day scrollable calendar row with an event-details dialog) is in review; 1.2.0 (9) adds the Now playing card (media sessions, needs notification access; on Google TV: `adb shell cmd notification allow_listener com.azura.tvlauncher/.MediaListener`). Test builds use `-PtvOnly=false` so phone-only testers can install. The tester list "Alpha testers" exists (your own address in it) and the opt-in link is https://play.google.com/apps/testing/com.azura.tvlauncher

1. **Review: approved** on 1 Nov 2026; the closed-testing release is live and the opt-in link works. Because the app is paid, testers would be charged $0.99, so a promotion "Closed test testers" with 50 one-time promo codes exists (Monetize → Promo codes → Download codes), valid 3 Nov 2026 to 31 Jan 2027. Give each tester one code; they redeem it in the Play Store (profile → Payments & subscriptions → Redeem code) and then install free.
2. **Find 12 testers** via a tester-swap community (r/AndroidClosedTesting on Reddit, the "Closed Testing" / "20 testers" Discord servers, testerscommunity.com): post the opt-in link (`TESTER_POST.md` has a ready post), test their apps in return, and add each tester's Gmail to the "Alpha testers" list (Closed testing → Alpha → Testers → the list's arrow → Add). Testers must install the app and stay opted in.
3. **14 days later**: Dashboard → "Apply for access to production". Phones and tablets stay supported in production (decided 1 Nov 2026), so the production bundle is built the same way as the test bundles, just with a higher versionCode.
4. **15% service fee**: the console notification "It's time to enroll for the 15% service fee" is Google's reduced-fee tier for the first USD 1M a year. Enrol under Manage account → Monetization setup; it halves Google's cut.

## 6. Before each later upload

Bump `versionCode` (and `versionName`) in `app/build.gradle.kts`, then `./gradlew bundleRelease` with the environment variables from the README. Commit the change.

## 7. Optional

- Test the Unsplash background with a real access key (unsplash.com/developers → New application → Access Key, paste under Settings › Background › Unsplash). That code path has not been exercised with a key.
- Try the app on another TV box or an Android TV emulator image at 720p; the layout was verified on the Streamer at four densities but not on a second device.
- When your own box moves to the Play version: uninstall the sideloaded copy first, re-enter keys, and generate a new Cielo key (the old one has already authenticated once). See README "Moving a sideloaded box to the Play build".
