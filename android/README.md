# PELAK Android v0.1

Android WebView shell for **https://pelakauto.com/**. The existing HTML site, Supabase database, listing workflows, and multilingual frontend remain unchanged.

## Build
GitHub Actions workflow: **Build PELAK Android APK**.
On a push that changes android/ or this workflow, or via workflow_dispatch, it uses Java 17, Android SDK 35, and Gradle 8.11.1 to run:

    gradle :app:assembleDebug --no-daemon

Then download the GitHub Actions Artifact **pelak-android-v0.1-debug-apk**. Extract the ZIP to get app-debug.apk.

To build locally, open this \`android/\` directory in Android Studio with JDK 17, AGP 8.9.2, Gradle 8.11.1, Android SDK 35.

## Capabilities and limitations
- Uses the existing website and login state through Android WebView.
- Camera/gallery file input, multi-select, external tel/sms/mailto links, Android back, offline retry.
- Minimum Android 7 (API 24), package com.pelakauto.app.
- App notifications via native Firebase Cloud Messaging are NOT implemented. Website push may not work inside WebView.
- APK is a debug/testing build, **not** a Play Store-ready signed release.
- Must test on a real Android phone before distributing.

## Persian guidance
See the downloadable source ZIP supplied in ChatGPT for the Persian guide and checklist.
