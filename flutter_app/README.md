# FangGu Flutter

This folder contains the Flutter version of FangGu.

## Prereqs
- Install Flutter SDK and add `flutter` to PATH.
- Run `flutter doctor` and fix any missing components.

## Bootstrap
From this folder:

```bash
flutter create .
flutter pub get
```

## AMap keys
Replace the keys in `lib/main.dart`:
- `androidKey`: your Android AMap key
- `iosKey`: your iOS AMap key

Then follow the AMap Flutter plugin setup (AndroidManifest/Info.plist).

## Run
```bash
flutter run
```

## Notes
- Search and external navigation hooks are placeholders and need platform-specific integration.
