# Kandy's Planner Widget

A native Android home-screen widget for [Kandy's Planner](https://kandyphoenix.github.io/kandys-planner/). Shows overdue count + today's/upcoming agenda items, pulled directly from the same Firestore doc the web app reads and writes (`wellness-tracker-127` / `wellness/servicesPlanner`) — no login, read-only.

- Refreshes every 30 min via WorkManager, plus a manual refresh (⟳) on the widget itself.
- Tapping the widget opens the full planner in your browser.
- Built with Jetpack Glance (Compose-based widget framework).

## Install

No Play Store listing — this is sideloaded. GitHub Actions builds a debug APK on every push:

1. Go to the repo's **Releases** page on your phone.
2. Download the latest `app-debug.apk`.
3. Open it — allow "install unknown apps" for your browser if prompted.
4. Long-press your home screen → **Widgets** → find **Kandy's Planner** → drag it on.

## Build locally

Requires JDK 17 + Android SDK (no local SDK was used to build this — CI does it via `gradle assembleDebug` on GitHub's Ubuntu runners, which ship with the Android SDK preinstalled).

```
gradle assembleDebug
```
