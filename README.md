# SG Expense

Personal Android expense tracker for Singapore: PayNow, GPay tap (Google Wallet), ShopBack Pay, UOB.

## Ways to add expenses
- **Manual**: Home › Add.
- **Auto-capture**: reads notifications from watched apps (Google Wallet, UOB TMRW, ShopBack, FairPrice Group, SimplyGo by default). UOB alerts "at BUS/MRT" are recorded as SimplyGo fares under Transport, and Stats shows a public-transport summary. Grant access in Settings › Auto-capture. A GPay tap that also triggers a UOB card alert is merged into one record (same amount, different app, within 10 minutes).
- **Images (OCR, on-device)**: Import › Images. Works with receipts and with screenshots of transaction lists or PayNow confirmations.
- **Excel / CSV**: Import › Excel / CSV. Recognises the UOB account and card exports (`.xls`, `.xlsx`, `.csv`, and HTML-style `.xls`); for anything else you pick the columns once and the app remembers them.

Everything goes through a review screen; likely duplicates come unticked.

## Backup
- Android Auto Backup puts the database in your Google account's backup.
- Settings › Back up now / weekly backup file: saves a JSON file anywhere the file picker reaches, including Google Drive.
- Export to `.xlsx` / `.csv`.

## Build
Needs JDK 17+ (Android Studio's bundled JBR works) and the Android SDK at `E:\Android` (see `local.properties`).

```bash
export JAVA_HOME="/e/Program Files/Android/Android Studio/jbr"
./gradlew :app:testDebugUnitTest   # parser / import unit tests
./gradlew :app:assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Build notes for this machine:
- `buildToolsVersion` is pinned to 36.1.0 because the SDK's 34.0.0 folder contains Linux binaries.
- The project path contains non-ASCII characters (`记账`), so `app/build.gradle.kts` copies test classes to an ASCII temp folder before unit tests run.

## Tuning auto-capture
Notification wording varies by app version. If a payment shows as "Ignored" in Settings › Capture log, copy its text and add a case to `capture/parsers/NotificationParsers.kt` plus a test in `CaptureParsersTest.kt`.
