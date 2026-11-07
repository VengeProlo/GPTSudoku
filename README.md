
## Build fix in v3

The latest GitHub Actions compiler log reported `Unresolved reference 'selectAllOnFocus'`
in `MainActivity.kt`. This was corrected to call Android's Java API directly:
`setSelectAllOnFocus(true)`. The Android SDK exposes this method on `TextView`,
which `EditText` inherits. No SDK or workflow version change is required for this fix.

# Sudoku Assist (rebuilt)

A rebuilt native Kotlin Android helper for Oakever Games' **Sudoku - Classic Sudoku Puzzle** (`easy.sudoku.puzzle.solver.free`). The app is independent and is not affiliated with Oakever Games.

## What is included

- Android MediaProjection live screen capture, started only after the Android system permission prompt.
- A floating SCAN control, hidden before the screenshot is taken.
- A movable/resizable square crop to isolate the Sudoku grid, with a portrait-friendly preview for full-screen phone captures.
- On-device ML Kit OCR of individual cells; failed or ambiguous reads stay blank for manual correction.
- An editable 9×9 review grid so OCR mistakes can be corrected.
- A Kotlin backtracking solver that rejects invalid puzzles and does not present a result unless it finds exactly one solution.
- A floating answer overlay showing only the originally blank cells.
- Optional keypad calibration followed by an explicit Accessibility-based tap feeder.
- GitHub Actions workflow to run unit tests and produce a debug APK. The workflow installs the required Android SDK API 36 and Build Tools packages explicitly.

## Build on GitHub

1. Create a repository and upload the **contents** of this folder (not the outer folder) to the repository root.
2. Keep `.github/workflows/build-apk.yml` at that exact path. When uploading through the GitHub website, create the file using **Add file → Create new file** and enter `.github/workflows/build-apk.yml` if hidden folders are awkward to upload.
3. Commit to `main` or `master`, or run the workflow manually from **Actions → Build Sudoku Assist APK**.
4. When the workflow succeeds, download the `sudoku-assist-debug-apk` artifact.

The workflow installs Android platform API 36 and Build Tools 36.0.0, uses JDK 17, AGP 8.10.1, Kotlin Gradle Plugin 2.2.20, and Gradle 8.14.3.

## Use the app

1. Open Sudoku Assist and tap **Start live screen scan**.
2. Grant **Display over other apps** if prompted, then accept Android's screen-capture prompt.
3. Return to Oakever Sudoku. Tap the floating **SCAN** control.
4. Crop the 9×9 grid, run OCR, and check/correct all detected clues.
5. Solve and verify. The solver only returns a result for one unique solution.
6. Choose **Show floating answer** or **Calibrate keypad for auto-fill**.
7. For auto-fill, tap the centres of the game's number keys 1 through 9 on the captured screenshot, enable **Sudoku Assist Auto Feeder** in Android Accessibility Settings, return to the helper, and tap **Start auto-fill**.

## Permissions and limitations

- Screen capture uses Android's MediaProjection consent. Android displays its ongoing screen-sharing notification while active.
- The floating scan and answer controls require **Display over other apps** permission.
- Auto-fill uses Android Accessibility gestures. It listens only for window-state package changes and does not request access to window content, read text, inspect UI hierarchies, or collect game data. It sends taps only after the user starts auto-fill and only when the Oakever Sudoku package is foreground.
- The tap feeder assumes the game accepts the sequence **tap empty cell → tap number**. It does not verify that the game accepted each digit. Keep the same orientation, display scale, board position, and keypad position as in the captured screenshot. If the layout changes, recapture and recalibrate.
- OCR is not infallible. Always review and correct the 81 cells before solving.
- Auto-fill requires a live screenshot from the same device/session. Screenshot import is supported for scan/solve/overlay, but feeder calibration is disabled for imported files.
- The project source and unit tests are provided; the APK is produced by your GitHub Actions run. A physical-device test is still required for a specific phone, Android version, and Oakever game layout.

## Toolchain

- Android Gradle Plugin: 8.10.1
- Kotlin Gradle Plugin: 2.2.20
- Gradle: 8.14.3
- JDK: 17
- compileSdk: 36
- targetSdk: 34
- minSdk: 26
