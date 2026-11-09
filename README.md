# SG Expense

A personal Android expense tracker built around how people pay in Singapore: **PayNow**, **GPay tap (Google Wallet)**, **ShopBack Pay**, **FairPrice Pay**, **SimplyGo (Bus/MRT)** and **UOB** cards and accounts.

Expenses come in four ways: typed in by hand, captured automatically from payment notifications, read from receipt photos and screenshots, or imported from bank statement files. Everything is stored on the phone.

- Android 8.0+ (minSdk 26), Kotlin + Jetpack Compose (Material 3)
- Amounts in SGD, stored as integer cents; all dates are bucketed in Singapore time

---

## Features

### 1. Manual entry
- Expense / Income / Refund toggle, amount, merchant or payee, date and time.
- Category chips (spending or income categories, depending on the type).
- Payment method chips: PayNow, GPay tap, ShopBack, FairPrice Pay, SimplyGo (Bus/MRT), Card, Cash, Bank transfer, Other.
- Optional account/card, note, and ShopBack cashback earned.
- Typing a merchant suggests a category from the keyword rules.
- Picking a category for a merchant teaches the app; see [Categorisation](#categorisation).
- Edit or delete any transaction. Auto-captured ones also show the original notification text.

### 2. Automatic capture from notifications
Reads notifications **only** from apps you tick in *Settings › Watched apps*. Everything else is dropped without being read.

| App | Package | What's recorded |
|---|---|---|
| Google Wallet | `com.google.android.apps.walletnfcrel` | GPay tap-to-pay: amount, merchant, card last 4 |
| UOB TMRW | `com.uob.mighty.app` (+ alt IDs) | Card transactions, PayNow / FAST transfers out, money received |
| ShopBack | `com.shopback.app` | ShopBack Pay payments; cashback is attached to the matching payment |
| FairPrice Group | `com.fairprice.mcomapp` | FairPrice Pay / Scan & Go payments and e-receipts |
| SimplyGo | `sg.com.transitlink` | Bus/MRT fare charges and EZ-Link top-ups |
| Any other app you tick | — | Generic parser: needs an S$ amount and a payment word ("paid", "debited"…) |

How capture works:
- **Notification filtering:** OTPs, promotions, PayNow requests, declined/failed payments, low-balance warnings and service notices are ignored.
- **Direction:** "received / credited" becomes Income, "refund / reversal" becomes Refund, everything else is an Expense.
- **Transit detection:** a card alert "at BUS/MRT SINGAPORE SG" (how SimplyGo bank-card fares appear) is recorded as a *SimplyGo* fare under *Transport*.
- **Duplicate merging:** one GPay tap often fires both a Google Wallet notification and a UOB card alert. Two captures are merged into one record when they have the same amount and type, come from different apps, and are within 10 minutes of each other. The merged record keeps the more specific payment method and the more descriptive merchant name, and fills in the card/account.
- **Repeat notifications:** an app re-posting the same notification within 5 minutes is ignored.
- **Recently captured:** captures from the last 24 hours are listed on the Home screen, with one-tap **Undo**.
- **Capture log:** *Settings › Capture log* keeps every notification from watched apps for 30 days, marked *Saved*, *Merged*, *Cashback* or *Ignored*. The text can be selected and copied, for adding new formats.
- **Pause switch:** capture can be paused without revoking notification access.
- **Updates:** apps added to the default list in later versions are switched on once, without re-adding apps you unticked.

### 3. Image import (on-device OCR)
*Import › Images* (pick up to 30), or share images into the app from Gallery, Files or Google Drive.
- Text is read on the phone with **Google ML Kit** (bundled Latin model, works offline).
- Lines are grouped into visual rows by position, so "TOTAL" and "12.50" printed in separate columns are read together.
- **Receipts:**
  - Picks GRAND TOTAL / NETT TOTAL / TOTAL (incl. GST) / AMOUNT DUE, and skips subtotal, GST, service charge, cash tendered and change.
  - Also finds the date, time and store name (top lines, skipping the address, GST reg no., UEN and phone number).
- **Screenshots:**
  - Transaction lists with date section headers ("06 Nov 2026"), signed amounts (−S$ / +S$) and descriptions on the same line or the line above.
  - Single payment confirmations, e.g. PayNow "Transfer successful / SGD 50.00 / To JOHN TAN".
  - SimplyGo journey history.
  - Year-less dates ("30 Oct") and "Today" / "Yesterday" are resolved.

### 4. Bank statement import (Excel / CSV)
*Import › Excel / CSV*, or share a file into the app.
- **Formats:** `.xlsx`, `.xls` (legacy BIFF), `.csv` (comma, semicolon or tab) and HTML tables saved as `.xls`. The format is detected from the file's contents, not its extension.
- **Auto-detects UOB layouts:**
  - Account history: *Transaction Date / Transaction Description / Withdrawal / Deposit*
  - Card statement: *Transaction Date / Posting Date / Description / Transaction Amount(Local)*; "CR" amounts become refunds
- **Other banks:** a header row is searched for in the first 40 rows using keywords (date, description, amount, debit/credit…).
- **Signed amount column:** a single signed column is understood either way (bank style, where minus is spending, or card style, where positive is spending).
- **Description cleanup:** rail prefixes like `PAYNOW-FAST OTHR`, `NETS QR` and `POS` are removed. The payment method is guessed from the text (PayNow, FairPrice Pay, SimplyGo, FAST/GIRO, ATM, card).
- **Unrecognised layouts:** you match the columns once in a dialog, and the mapping is remembered for files with the same header.

### 5. Review before saving
Image and file imports land on a review screen first:
- Each row shows date, merchant, amount, payment method and source.
- Tap a row to fix the amount, merchant, date, type, method or category.
- Rows that look like duplicates of existing data (same amount within ±1 day) are flagged and left unticked.
- There are *All* / *None* toggles and a running total of what's selected.

### 6. History and stats
- **History:**
  - Browse month by month, grouped by day with daily totals.
  - Search by merchant, note or amount.
  - Filter by category, payment method and source (Manual / Auto-captured / Image / File).
- **Stats:**
  - Month totals for spent, income and cashback.
  - Category donut with percentages, a spend-by-payment-method bar chart and a daily spending chart.
  - A **public transport (SimplyGo) card**: month's fares, travel days and average per day.
- **Home:** this month's spend, today's spend, income, recently captured items and recent transactions.

### 7. Backup, restore and export
- **Android Auto Backup:**
  - The database and settings are included in the phone's Google backup and restored on reinstall or a new phone.
  - The database uses TRUNCATE journal mode, so the backup copies a consistent single file.
- **Back up now:** saves a JSON backup through the system file picker, so you can choose Google Drive.
- **Weekly backup:** choose a file once (e.g. on Google Drive); a WorkManager job overwrites it every week. The last run and any error are shown in Settings.
- **Restore:** reads a JSON backup and replaces all data, after a confirmation.
- **Export:** `.xlsx` or `.csv` for this month, last month, this year or everything. The CSV is UTF-8 with a BOM, so Excel opens it correctly.

---

## Categorisation

Default categories:
- **Spending:** Food & Drinks, Groceries, Transport, Shopping, Bills & Utilities, Entertainment, Health, Travel, Transfers, Others
- **Income:** Income, Cashback & Refunds

You can add, rename, recolour or delete categories and change their emoji. Deleting a category keeps its transactions but leaves them uncategorised.

Each category comes with Singapore merchant keywords, for example:
- **Groceries:** FAIRPRICE, NTUC, SHENG SIONG, COLD STORAGE, DONKI…
- **Transport:** GRAB, GOJEK, BUS/MRT, SIMPLYGO, SMRT…
- **Food & Drinks:** KOUFU, TOAST BOX, YA KUN, FOODPANDA…
- **Bills & Utilities:** SINGTEL, SP SERVICES…

How the matching works:
- **Cleanup first:** merchant names are normalised by upper-casing, removing punctuation and dropping prefixes like `PAYNOW FAST`, `NETS QR` and `POS`, and suffixes like `SINGAPORE SG` and `PTE LTD`.
- **Learning:** when you change a transaction's category, the merchant's first two meaningful words are learned as a new rule. Learned rules win over built-in ones, and longer keywords win over shorter ones.
- **Short keywords:** keywords of 4 characters or fewer (BUS, MRT, KFC…) must match a whole word, so "BUSINESS TIMES" isn't Transport.
- **Fallbacks when nothing matches:**

  | Transaction | Category |
  |---|---|
  | Refund | Cashback & Refunds |
  | Income | Income |
  | PayNow / bank transfer | Transfers |
  | SimplyGo | Transport |

---

## Permissions and privacy

- **Notification access** (granted by you in system settings): needed for auto-capture. Only watched apps' notifications are processed.
- **No account, no server:** the app doesn't upload your data anywhere. It only leaves the phone through Android's own backup, or a backup/export file you choose to save.
- **Libraries add INTERNET:** the final manifest includes `INTERNET`, which Google ML Kit and WorkManager pull in. OCR itself runs on the device, but ML Kit may send anonymous usage logs to Google.
- **Photos and files:** read through the system photo picker and file picker. No storage permission is requested.
- **Battery:** some phones (e.g. Samsung) stop background services. *Settings › Allow background activity* links to the battery settings so capture keeps running.

---

## Project structure

```
app/src/main/java/com/sgexpense/
├── SgExpenseApp.kt / MainActivity.kt   App container (manual DI), navigation, share-intent handling
├── data/
│   ├── model/        Transaction, Category, MerchantRule, CaptureLog, enums (PaymentMethod, TxType, Source)
│   ├── db/           Room database + DAOs
│   └── repository/   ExpenseRepository, Categorizer (keyword matching / learning), Defaults (seed data)
├── capture/
│   ├── PaymentNotificationListener.kt   NotificationListenerService
│   ├── CaptureProcessor.kt              parse → de-dup/merge → save → log
│   ├── Deduplicator.kt                  cross-app merge rules
│   ├── CaptureSettings.kt               watched apps, notification-access checks
│   └── parsers/NotificationParsers.kt   Google Wallet, UOB, ShopBack, FairPrice, SimplyGo, generic
├── importer/
│   ├── ocr/          OcrEngine (ML Kit), RowGrouper, ReceiptParser, ScreenshotListParser, OcrAnalyzer
│   ├── sheet/        SheetReader (content sniffing), Xlsx/Xls/Csv/HtmlTable readers, StatementMapper
│   └── ImportService.kt                 review items, duplicate flags, saved column mappings, commit
├── backup/           BackupManager (JSON, CSV, XLSX), XlsxWriter, weekly BackupWorker
├── ui/               Compose screens: home, edit, list, stats, importer, settings, theme, components
└── util/             Money (cents parse/format), DateParsing (SG date formats), Time (SGT helpers)
```

Main libraries: Jetpack Compose (Material 3), Navigation Compose, Room, WorkManager, DocumentFile, ML Kit Text Recognition (bundled), JExcelApi (`.xls`).

---

## Build and run

Requirements: JDK 17+ (Android Studio's bundled JBR works) and the Android SDK with platform 35.

1. Create `local.properties` with your SDK path (it's git-ignored), for example:
   ```properties
   sdk.dir=C\:\\Users\\you\\AppData\\Local\\Android\\Sdk
   ```
2. Build, test and install:
   ```bash
   ./gradlew :app:testDebugUnitTest   # unit tests
   ./gradlew :app:assembleDebug       # → app/build/outputs/apk/debug/app-debug.apk
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
3. On the phone, open the app and tap **Set up**, or go to *Settings › Grant notification access*, to turn on auto-capture.

Or open the project in Android Studio and press Run.

Build notes:
- `buildToolsVersion` is pinned to `36.1.0` in `app/build.gradle.kts`. Install it with the SDK Manager, or change it to a version you have.
- If the project path contains non-ASCII characters (e.g. `E:\记账\…`), `app/build.gradle.kts` copies the test classpath to an ASCII temp folder before tests run. Gradle's test worker on Windows can't load classes from such paths.
- The `release` build type is minified and signed with the debug key, for sideloading only. Use your own signing config before distributing.

---

## Tests

Unit tests (JVM, `./gradlew :app:testDebugUnitTest`):
- **`CaptureParsersTest`** covers:
  - Notification parsing for Google Wallet, UOB card / PayNow out / PayNow in, ShopBack payment and cashback, FairPrice Pay, SimplyGo fares and top-ups, and the generic parser.
  - Filtering of OTP, promotion and request notifications.
  - Cross-app de-duplication and merging.
- **`ImportParsersTest`** covers:
  - Money and date parsing.
  - OCR row grouping, receipt totals, transaction-list screenshots, PayNow confirmations and SimplyGo journeys.
  - UOB account CSV and card-statement (HTML `.xls`) mapping, and the `.xlsx` round trip.
  - The BUS/MRT statement line, and category matching including short-keyword boundaries.

---

## Limitations and tuning

- **Notification wording is best-effort.** It's based on known formats and can change with app updates. If a real payment shows as *Ignored* in *Settings › Capture log*, copy the text and add a case to `capture/parsers/NotificationParsers.kt` with a test in `CaptureParsersTest.kt`.
- **SimplyGo bank-card fares are daily totals.** Banks combine them into one charge per day, sometimes delayed, so captured fares are per day rather than per ride. For ride-level detail, import screenshots of SimplyGo journey history.
- **SGD only:** foreign-currency amounts aren't converted.
- **OCR accuracy depends on image quality.** Always check the review screen before importing.
