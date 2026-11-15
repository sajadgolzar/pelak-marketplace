# PELAK Native Android — v0.2 Preview

Android project uses **Kotlin + Jetpack Compose** and is **not a WebView**.
It communicates directly with Supabase using a publishable client key and the database's existing Row Level Security (RLS).

## What v0.2 implements
- Public car listings and on-server searchable brand/model/trim/title/city, pagination in 100-result chunks
- First image and a scrolling photo gallery, short-lived signed URLs, private draft photos accessible to their owner
- Email OTP sign in and encrypted session retention using Android Keystore, automatic refresh
- Draft creation, draft editing (only while listing_status=draft) and multiple photo picker/compressed uploads to the private bucket
- Native buyer/seller chat and inbox, using the existing validated `pelak_send_listing_message` RPC and participant-read RLS
- Android FCM SDK initialization, registration/unregistration RPCs and opt-in Android 13+ notification permission

## Remaining production blockers / known limitations
- The APK is **debug-signed**, **not production/release signed**; it must be tested on a real Samsung device before public recommendation.
- FCM **delivery is not active yet**: server job `pelak-android-fcm-every-minute` is disabled pending a securely configured Firebase Service Account. Also register the preview package ID `com.pelakauto.nativepreview` in Firebase (or change to a properly registered release ID) and test device token issuance.
- Photos require camera roll access through Android's picker; direct in-app camera, EXIF rotation handling, image deletion/reordering and robust offline upload are not finished.
- Editing published/sold/needs-revision listings requires a server-approved moderation flow, not a bypass of draft-only RLS. Current editor deliberately limits changes to user-owned drafts.
- Search has server-side pagination but only basic text filters; favorites, advanced filters, push deep links, complete showroom/admin functions, rich media chat, offline reading, and complete FA/EN/AR/KA localization are still unfinished.
- Photo privacy is controlled by the existing Storage RLS; no client-side masking of licence plates. Admin review remains required before publishing.
- Existing WebView version in `android/` remains only as a rollback, **not** the product.
- Native preview has a different package ID from earlier WebView release. The existing `downloads/PELAK-Native-v0.1-preview.apk` is retained to allow rollback.

## Build
GitHub Actions: `Build PELAK Native Android Preview`.
On the branch `feature/native-v02-2026-11-15` and `main` it builds with JDK 17 and Gradle 8.11.1.
After a successful merge to main, CI publishes `downloads/PELAK-Native-v0.2-preview.apk` automatically.

## Manual regression checks before launch
- Clean install and update on a Samsung device (Android 7+), then login with OTP and restart app
- Search a model outside the first 100 listings, move through pagination, open signed photo gallery
- Register a draft with 1–8 photos, revisit and edit the draft, ensure no duplicate row or orphaned file after retry
- Chat using two real separate user accounts, verify buyer can initiate and seller can only reply to their conversation
- Verify anonymous and authenticated Storage permissions and check draft photo privacy
- Configure FCM Service Account securely, enable server dispatch only after testing, grant notifications on Android 13+
- Sign out, sign in again, verify messages and FCM token ownership, test offline/slow connectivity

## Update notices (native + web)
- `pelak-release.json` is the public, same-origin version manifest. The Android app checks it at startup and offers a manual check in its header. The website checks it on load, every 15 minutes while visible, and on returning to the tab; a manual check is available in the footer.
- **Release discipline:** increment Android `versionCode` and `versionName` in `app/build.gradle` **and** `pelak-release.json` when publishing a newer APK. Increment `web.build` in `pelak-release.json` and `bundledWebBuild` in `pelak-platform-downloads.js` together when releasing new web assets. Keep the public download URL in sync if the filename changes.
- Android's button opens the fixed PELAK HTTPS APK download URL. It does not silently install an APK or claim Play Store in-app updates.
- **Production blocker:** CI debug APKs may be signed with different temporary keys. Configure one secure persistent release-signing key, validate matching `applicationId`, and test upgrade-in-place with data retained on a physical Samsung before promising seamless updates. Do not commit a signing keystore or passwords.
- If version metadata is unreachable, the app/site retains normal functionality; manual checks show a retry message.

## Secure Android release signing and update push

The normal GitHub Actions debug build remains available for compilation checks, but **must not be automatically published as an upgrade**. Debug APK signatures can differ between CI runners. The manual workflow `Build signed PELAK Android release (manual)` uses a stable private keystore stored **only in GitHub Actions Secrets** and verifies the resulting APK signature.

**One-time owner setup on a trusted computer:** create and safely back up a dedicated release keystore with `keytool -genkeypair -v -keystore pelak-release.jks -alias pelak-release -keyalg RSA -keysize 3072 -validity 10000`. Keep the keystore and passwords offline and in a secure backup; losing the key means existing installations cannot be upgraded in place. Never commit the keystore, base64 representation, or passwords to Git.

Add four repository Actions secrets:
- `PELAK_ANDROID_KEYSTORE_B64`: single-line base64 of the complete keystore file
- `PELAK_ANDROID_STORE_PASSWORD`: keystore password
- `PELAK_ANDROID_KEY_ALIAS`: key alias (e.g. `pelak-release`)
- `PELAK_ANDROID_KEY_PASSWORD`: private-key password

Then manually run the signed-release workflow from the default branch. It produces a signed APK artifact for device QA, **not a public download**. Before publishing: increment `versionCode` and update `pelak-release.json`; verify APK certificate fingerprints and install/upgrade on a Samsung; confirm user data persists. The first stable signed release may **not** upgrade a previously debug-signed preview APK, even with the same application ID; test this and give users migration guidance rather than promising a seamless update.

FCM `data` payload for an update notification: `{"kind":"app_update","version_code":"3"}`. The native receiver ignores notices for installed/newer versions and never trusts a URL from the push payload. **Server delivery remains disabled until Firebase service-account credentials are configured securely and a controlled end-to-end device test succeeds.** Do not store Firebase private keys in the client app or public repository. Android 13+ requires user permission to display notifications.

## Mobile-only signing key bootstrap (one-time, not an APK release)

For an owner using only iPhone/Samsung, the manual GitHub Actions workflow `Create encrypted PELAK signing-key backup (one time)` avoids typing shell commands on a mobile terminal.

1. In repository **Settings → Secrets and variables → Actions**, create **four** secrets: `PELAK_ANDROID_STORE_PASSWORD` (strong random password), `PELAK_ANDROID_KEY_PASSWORD` (use the same password for PKCS12), `PELAK_ANDROID_KEY_ALIAS` (value `pelak-release`), and `PELAK_ANDROID_BACKUP_PASSWORD` (a **different**, strong 20+ character password). Never send secret values over chat or commit them.
2. In **Actions**, manually run the bootstrap workflow **exactly once**. It generates the signing key on an ephemeral GitHub runner, uploads **only AES-256-encrypted keystore backup** and public certificate fingerprint as a short-lived Actions artifact, and removes plaintext key files. The encrypted artifact is not a usable signing secret by itself.
3. Download the artifact to the iPhone Files app, save in `On My iPhone/PELAK-Backup`, and also keep a separate secure offline backup. Retain the backup decryption password and store/key passwords in a password manager; the artifact expires after seven days.
4. **Do not run the bootstrap a second time** after releasing an APK: it would create a different certificate and break upgrade compatibility. Before shipping any signed APK, recover/decrypt the original key in a trusted environment and register its base64 as `PELAK_ANDROID_KEYSTORE_B64` repository secret. The separate manual signed-release workflow requires that secret. If there is no secure way to decrypt and register it on the phone, **stop** rather than publish a release that cannot be maintained.
5. Treat GitHub Codespaces and GitHub-hosted runners as remote computers; do not claim that the owner has sole possession of the unencrypted key during generation. Do not paste passwords into chats, terminal history, GitHub issues, or screenshots.

This bootstrap prepares an encrypted backup only. It **does not** activate FCM server delivery, upload a public APK, create a signed release, or automatically configure the base64 keystore secret.
