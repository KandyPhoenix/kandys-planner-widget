# Kandy's Planner Widget

A native Android home-screen widget for [Kandy's Planner](https://kandyphoenix.github.io/kandys-planner/). Shows overdue count + today's/upcoming agenda items, read-only. Since the Firestore rules were locked (2026-09-29) the widget no longer reads the doc itself: the planner Worker (`planner.phoenixmethod.workers.dev/widget`) reads it with a service account and serves a copy, gated by a key you enter once in the app.

- Refreshes every 30 min via WorkManager, plus a manual refresh (⟳) on the widget itself.
- Tapping the widget opens the full planner in your browser.
- Built with Jetpack Glance (Compose-based widget framework).

## Install

No Play Store listing — this is sideloaded. GitHub Actions builds a debug APK on every push:

1. Go to the repo's **Releases** page on your phone.
2. Download the latest `app-debug.apk`.
3. Open it — allow "install unknown apps" for your browser if prompted.
4. Open the **Kandy's Planner** app icon once: it asks for the **widget key** (the value of the Worker's `WIDGET_KEY` secret). Paste it and tap Save.
5. Long-press your home screen → **Widgets** → find **Kandy's Planner** → drag it on.

To change the key later, clear the app's storage (Settings → Apps → Kandy's Planner → Storage → Clear) and open the app again.

## Build locally

Requires JDK 17 + Android SDK (no local SDK was used to build this — CI does it via `gradle assembleDebug` on GitHub's Ubuntu runners, which ship with the Android SDK preinstalled).

```
gradle assembleDebug
```
