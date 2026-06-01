@echo off
REM Phase 1 verification helper
REM
REM Usage:
REM   pull_and_verify.bat         -- Phase 1-A (pure Doze test, no state log)
REM   pull_and_verify.bat phase1b -- Phase 1-B (pause/resume test, with state log)
REM
REM Prerequisites:
REM   - adb in PATH (Android SDK platform-tools)
REM   - Python 3.8+ in PATH
REM   - Tap the "Export" button in the app FIRST
REM     (copies segments + state_log to Android/data/com.rokuonsumm/files/verify/)

set PKG=com.rokuonsumm
set MODE=%~1
if "%MODE%"=="" set MODE=phase1a

echo [1/2] Pulling verify/ from device ...
rmdir /s /q verify 2>nul
adb pull /sdcard/Android/data/%PKG%/files/verify .
if errorlevel 1 (
    echo.
    echo ERROR: adb pull failed.
    echo Make sure USB debugging is on and the Export button was tapped first.
    pause
    exit /b 1
)

REM After adb pull, structure is: verify\segments\  and  verify\state_log.csv
echo [2/2] Running verify_segments.py ...
if "%MODE%"=="phase1b" (
    python verify_segments.py verify\segments --state-log verify\state_log.csv %*
) else (
    python verify_segments.py verify\segments %*
)

pause
