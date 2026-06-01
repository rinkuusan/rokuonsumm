# 録音サマリーアプリ 全フロー試走スクリプト
# 端末再接続後にこれ1本でホーム→タイムライン→詳細→設定→ストレージを試走し
# スクショとop_log.txtを集める

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$pkg = "com.rokuonsumm"
$out = "C:\Users\tobichiru\Desktop\Claude\rokuonsumm\test_run"
New-Item -ItemType Directory -Force $out | Out-Null

function Snap($name) {
    & $adb exec-out screencap -p > "$out\$name.png"
    Write-Host "  📸 $name.png"
}

function Tap($x, $y, $label = "") {
    Write-Host "  TAP ($x,$y) $label"
    & $adb shell input tap $x $y
    Start-Sleep -Milliseconds 600
}

function Back() {
    & $adb shell input keyevent KEYCODE_BACK
    Start-Sleep -Milliseconds 500
}

Write-Host "=== 全フロー試走スタート ==="

# 0. デバイス確認
$devs = & $adb devices
if ($devs -notmatch "device$") { Write-Error "デバイス未接続"; exit 1 }

# 1. 既存APKをinstall
Write-Host "[1/8] APKインストール"
& $adb install -r "C:\Users\tobichiru\Desktop\Claude\rokuonsumm\app\build\outputs\apk\debug\app-debug.apk" | Out-Null

# 2. アプリ起動
Write-Host "[2/8] アプリ起動"
& $adb logcat -c
& $adb shell am start -n "$pkg/.ui.MainActivity"
Start-Sleep 3
Snap "01_home"

# 3. ホーム画面の要素確認: 設定ボタンタップ(右上)
Write-Host "[3/8] 設定画面へ"
Tap 980 130 "btnSettings"
Snap "02_settings"

# 4. ストレージ管理を開く (スクロール下のリンク)
Write-Host "[4/8] ストレージ管理画面へ"
# 下にスクロール
& $adb shell input swipe 500 1500 500 500
Start-Sleep 1
Snap "03_settings_scrolled"
# btnStorage タップ
Tap 540 1500 "btnStorage"
Snap "04_storage"

# 5. 設定→ホーム戻り
Back
Start-Sleep 1
Back
Start-Sleep 1
Snap "05_back_home"

# 6. 日付カードタップ (リスト先頭)
Write-Host "[6/8] 日付タップでタイムラインへ"
Tap 540 300 "first_day_card"
Snap "06_timeline"

# 7. 「要約する」ボタンタップ (上部、ない場合はスキップ)
Write-Host "[7/8] 要約する ボタンタップ"
Tap 900 350 "btnSummarize"
Start-Sleep 2
Snap "07_summarizing"
Start-Sleep 8
Snap "07b_after_summary"

# 8. 段落タップで詳細画面へ
Write-Host "[8/8] 段落タップで発言詳細へ"
Tap 540 700 "paragraph"
Start-Sleep 1
Snap "08_detail"

# クラッシュログとop_logを引っこ抜く
Write-Host "=== ログ引き取り ==="
& $adb shell run-as $pkg cat files/op_log.txt > "$out\op_log.txt" 2>$null
& $adb shell run-as $pkg cat files/crash_log.txt > "$out\crash_log.txt" 2>$null
& $adb logcat -d -s AndroidRuntime:E OpLog:V > "$out\logcat.txt"

Write-Host "完了。$out 配下に成果物。"
