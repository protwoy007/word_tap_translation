# Word Tap Translation

An Android app that translates English words and sentences into Bangla inside any other app, without copying and pasting. It runs as an Android Accessibility Service and shows a small popup over the app you are reading.

## Features

- **Triple-tap a message** in any chat app. The whole sentence is highlighted in the popup.
- **Tap a word** in the popup. It is highlighted, then the English meaning appears, then the Bangla translation.
- **Long-press the highlighted sentence** in the popup to translate the whole sentence into Bangla.
- **Select a word or phrase** with the normal text selection (long-press) in apps that support it. The popup opens automatically.
- **Speak** button reads the word or sentence aloud.
- Idle until you use it. There is no floating button and no background polling.
- First launch shows a setup screen for the accessibility service, battery, and auto-start settings.

## How it works

- The Flutter app only provides the setup and status screens. It has no plugins, which keeps the APK small.
- The popup and detection are native Kotlin in `WordTapService.kt`, an `AccessibilityService` that listens for text selection and click events.
- The popup is drawn with an accessibility overlay window, so no "draw over other apps" permission is needed.
- Translations and meanings come from online services:
  - Bangla translation: Google Translate web endpoint (`translate.googleapis.com`, unofficial, no API key), with MyMemory as a fallback
  - English meaning: Free Dictionary API (`dictionaryapi.dev`)
  - Results are cached in memory for the session.

## Permissions

| Permission | Why |
|---|---|
| Accessibility service | Read the text you select or tap, and show the popup. You turn it on yourself in Android settings. |
| Internet | Fetch translations and meanings. |
| Ignore battery optimizations | Keep the service alive on phones that kill background services. |

## Privacy

- The text you select or triple-tap is sent to the translation and dictionary services listed above. Do not use the app on private text you do not want to leave your phone.
- The service ignores password fields.
- The app does not store, log, or upload anything else.

## Limitations

- Android does not tell an accessibility service where your finger touched, so a triple-tap cannot pick a single word. You pick the word in the popup instead.
- Apps that use double-tap for reactions (Messenger, Telegram) may still send a reaction on the second tap of a triple-tap.
- Apps that do not expose their message text to accessibility will not trigger the popup.
- The Google Translate web endpoint is unofficial and may change or be rate limited.
- Requires internet. There is no offline mode.
- Android only. iOS does not allow this kind of system-wide overlay.

## Setup

Requirements: Flutter SDK, Android SDK, and an Android phone or emulator.

```
flutter pub get
flutter run
```

Build a small release APK:

```
flutter build apk --release --split-per-abi --target-platform android-arm64
```

The APK is created at `build/app/outputs/flutter-apk/app-arm64-v8a-release.apk`.

## First run on the phone

1. Open the app. The setup screen appears.
2. Step 1: open Accessibility, find Word Tap Translation, and switch it on.
3. Step 2: if the switch is blocked, open App info, tap the top-right menu, choose **Allow restricted settings**, then repeat step 1.
4. Step 3: allow the battery request so the service keeps running.
5. Step 4: allow auto-start if your phone has that option.

If you reinstall the app, switch the accessibility service off and on again.

## Project structure

```
lib/main.dart                                  Setup and home screens
android/app/src/main/kotlin/.../MainActivity.kt   Opens settings and checks permission status
android/app/src/main/kotlin/.../WordTapService.kt Accessibility service and popup
android/app/src/main/res/xml/accessibility_config.xml   Accessibility service configuration
android/app/src/main/AndroidManifest.xml       Service and permission declarations
```
