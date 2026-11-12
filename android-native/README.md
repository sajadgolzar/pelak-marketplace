# PELAK Native Android — v0.1 Preview

This is a **separate, genuinely native Android project** using Kotlin and Jetpack Compose.
**No WebView and no dependency on pelakauto.com**. The app talks directly to the existing Supabase API.
It does not replace the existing `android/` WebView v0.3 test app or `index.html`.

## Native preview features
- Public list of published car listings (server RLS), search by brand/model/title/city
- Native listing cards, details, price in toman for IRR listings, phone dialer
- Public car images via short-lived signed URLs where the public Storage policy allows them
- Email one-time-code authentication, handled by Supabase Auth
- Save text-only car **draft** directly to Supabase for review, view "my listings"
- Handles loading, no listings, and network errors without opening a browser
- RTL Persian design with five-tab navigation

## Limitations — do not advertise as production-ready
- Debug/preview package `com.pelakauto.nativepreview`, separate from `com.pelakauto.app`
- Sign-in session is held in memory, so restarting requires logging in again
- No photo picker/upload, listing editing, full messaging, dealer management, FCM or admin tools **yet**
- Native listing submission saves a **draft only**; public publication still requires review
- Current user-facing date/price formats, auth, and seller flows require real Samsung device tests
- A working network connection to Supabase is required; offline cannot create or publish listings
- Secrets must not be committed. The included Supabase publishable key is intentionally public, scoped by RLS.

## CI build
GitHub Actions: `Build PELAK Native Android Preview` from `feature/pelak-native-android-mvp-2026-11-12`.
Artifact name: `pelak-native-v0.1-debug-apk`. The debug signing key is not a release key.

## Roadmap
1. Validate public listings/search and email OTP with RLS on a physical Android phone.
2. Implement photo-picker + safe multi-photo draft uploads, seller edit/cancel and support.
3. Add native chat/inbox, favorites, notification channels and FCM token registration.
4. Add complete Persian/English/Arabic/Georgian localization, showroom, admin.
5. Performance, security, release signing, Google Play testing before production swap.

## Project coexistence
`android/` = previous WebView-based APK `com.pelakauto.app`
`android-native/` = standalone native preview `com.pelakauto.nativepreview`.
Both share **Supabase data and RLS**. No backend migrations are required by this preview.
