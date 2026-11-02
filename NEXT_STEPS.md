# What is left to publish TV Launcher (your part)

Everything below needs your accounts or your decisions, so it could not be done for you. In order:

## 1. Keep the upload key safe (5 minutes)

`~/android-toolchain/keys/tvlauncher-upload-key-backup.zip` holds the keystore and its passwords. Put it in your password manager (as an attachment) or an encrypted cloud folder. If it is ever lost, the published app can never be updated again.

## 2. GitHub — done

The code is public at https://github.com/schwerko/tv-launcher (MIT licence). The push token is stored in the macOS keychain, so later pushes are just `git push` from the project folder. The privacy-policy URL for Play is https://github.com/schwerko/tv-launcher/blob/main/PRIVACY.md.

## 3. Google Play developer account — done

Developer account "Schwerko" is approved. The app exists in the console as **TV Launcher Azure** (`com.azura.tvlauncher`, paid). Console link: https://play.google.com/console/u/0/developers/8852856123804392108/app/4975636464627548772/app-dashboard

## 4. Console set-up — 10 of 13 dashboard tasks done (1 Nov 2026)

Done: store listing (descriptions, icon, feature graphic, 6 phone screenshots, TV banner, 6 TV screenshots), Android TV form factor added, privacy policy, ads, sign-in details, target audience (18+), data safety, advertising ID, government, financial, health, category (Tools), contact details (email azur.azapagic@gmail.com and the GitHub page, both shown publicly on Play), closed-testing track "Alpha" with the 1.1 (2) bundle uploaded as a draft release and all 178 countries selected.

Still yours (each needs a click that accepts terms or your banking details):

1. **Content rating**: App content → Content ratings → Start questionnaire → enter your email, pick **All Other App Types**, tick the IARC terms box, Next. Answers for the questions: no to violence, sexuality, language, drugs, gambling, user interaction/sharing of location, and in-app purchases; "yes" only if it asks whether users can communicate with third-party services (the AI assistant). Submit.
2. **Android TV opt-in**: Test and release → Advanced settings → Form factors → Android TV → "Opt-in to Android TV and agree to the review policy" → Opt-in.
3. **Merchant account**: Dashboard → "Create a merchant account" (bank account and tax details; Google pays out monthly). Then Monetize → App pricing → set USD 0.99 (Play's nearest price point to $1) and let it convert the other currencies.
4. **Testers**: Closed testing → Alpha → Testers tab → create an email list with at least 12 Gmail addresses, save, and copy the opt-in link for them.
5. **Send for review**: Publishing overview → "Send changes for review". The 14-day clock starts when the testers opt in.

## 6. Before each later upload

Bump `versionCode` (and `versionName`) in `app/build.gradle.kts`, then `./gradlew bundleRelease` with the environment variables from the README. Commit the change.

## 7. Optional

- Test the Unsplash background with a real access key (unsplash.com/developers → New application → Access Key, paste under Settings › Background › Unsplash). That code path has not been exercised with a key.
- Try the app on another TV box or an Android TV emulator image at 720p; the layout was verified on the Streamer at four densities but not on a second device.
- When your own box moves to the Play version: uninstall the sideloaded copy first, re-enter keys, and generate a new Cielo key (the old one has already authenticated once). See README "Moving a sideloaded box to the Play build".
