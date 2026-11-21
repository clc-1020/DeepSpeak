# 陪聊 App 真机部署 / 测试脚本
# 常用：
#   .\tools\deploy.ps1                                   # 重编 + 安装 + 启动
#   .\tools\deploy.ps1 -SkipBuild                        # 用已有 APK 安装启动
#   .\tools\deploy.ps1 -LogLines 400 -Shot out.png       # 安装启动 + 抓日志 + 截图
#   .\tools\deploy.ps1 -RunTests                         # 跑全部 connectedAndroidTest（跑完自动重装 App）
#   .\tools\deploy.ps1 -RunTests -TestClass com.companion.chat.data.embedding.EmbeddingAcceptanceTest   # 只跑某个测试类
#
# 关键：connectedAndroidTest 跑完会自动卸载 App（AGP 默认行为），本脚本 -RunTests 会在测试后自动重装并启动。
param(
    [switch]$SkipBuild,
    [int]$LogLines = 0,
    [string]$ShotPath = "",
    [string]$Serial = "dcfd02b4",
    [switch]$RunTests,
    [string]$TestClass = ""
)
$ErrorActionPreference = 'Stop'
$proj = 'C:\Users\clc\.deepseek-project\dsh-project'
$env:JAVA_HOME = 'C:\ai-deepseek\jdk17'
$env:ANDROID_HOME = 'C:\ai-deepseek\android-sdk'
$env:ANDROID_SDK_ROOT = 'C:\ai-deepseek\android-sdk'
$adb = 'C:\ai-deepseek\phone-tools\platform-tools\adb.exe'
$apk = Join-Path $proj 'app\build\outputs\apk\debug\app-debug.apk'
$act = 'com.companion.chat/com.companion.chat.ui.MainActivity'

function Ensure-Awake {
    & $adb -s $Serial shell input keyevent KEYCODE_WAKEUP 2>$null | Out-Null
    & $adb -s $Serial shell wm dismiss-keyguard 2>$null | Out-Null
}
function Install-And-Launch {
    if (-not (Test-Path $apk)) { Write-Host "APK missing, build first" -ForegroundColor Red; return 1 }
    Ensure-Awake
    & $adb -s $Serial install -r $apk
    & $adb -s $Serial shell am start -W -n $act | Out-Null
    return 0
}

$testExit = 0

if (-not $SkipBuild) {
    Write-Host "== building ==" -ForegroundColor Cyan
    Push-Location $proj
    cmd /c ".\gradlew.bat assembleDebug --no-daemon --console=plain 2>&1"
    $code = $LASTEXITCODE
    Pop-Location
    if ($code -ne 0) { Write-Host "BUILD FAILED ($code)" -ForegroundColor Red; exit $code }
}

if ($RunTests) {
    # ⚠️ 数据保护（2026-11-20 事故）：connectedAndroidTest 会让 AGP 卸载被测 App，
    # 卸载会删掉 /data/data/com.companion.chat 整个目录 → 会话/记忆/API Key 全丢且无法恢复。
    # 两道防线：① gradle.properties 的 leaveApksInstalledAfterRun=true（不卸载）；
    #          ② 这里无条件先备份、跑完再恢复（万一 AGP 仍卸载）。
    Write-Host "== backing up app data before instrumented test ==" -ForegroundColor Cyan
    $backupOut = & (Join-Path $proj 'tools\app-data-backup.ps1') -Backup -Serial $Serial 2>&1
    $backupOut | ForEach-Object { Write-Host "   $_" }
    $backupDir = ""
    foreach ($line in $backupOut) {
        if ("$line" -match 'backup -> (.+?)\s+\(') { $backupDir = Split-Path $Matches[1] -Leaf }
    }
    if ($backupDir -eq "") { Write-Host "WARNING: backup failed, data may be lost by uninstall" -ForegroundColor Yellow }

    Write-Host "== running connectedDebugAndroidTest ==" -ForegroundColor Cyan
    Ensure-Awake
    & $adb -s $Serial shell svc power stayon true 2>$null | Out-Null
    Push-Location $proj
    if ($TestClass -ne "") {
        cmd /c ".\gradlew.bat app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=$TestClass --no-daemon --console=plain 2>&1"
    } else {
        cmd /c ".\gradlew.bat app:connectedDebugAndroidTest --no-daemon --console=plain 2>&1"
    }
    $testExit = $LASTEXITCODE
    Pop-Location
    Write-Host "test exit = $testExit" -ForegroundColor Yellow
    Write-Host "== reinstalling app after test ==" -ForegroundColor Cyan
    Install-And-Launch | Out-Null
    if ($backupDir -ne "") {
        Write-Host "== restoring app data from $backupDir ==" -ForegroundColor Cyan
        & (Join-Path $proj 'tools\app-data-backup.ps1') -Restore -From $backupDir -Serial $Serial 2>&1 | ForEach-Object { Write-Host "   $_" }
    }
    & $adb -s $Serial shell svc power stayon false 2>$null | Out-Null
} else {
    Write-Host "== install + launch ==" -ForegroundColor Cyan
    Install-And-Launch | Out-Null
}

Start-Sleep -Seconds 3

if ($LogLines -gt 0) {
    $log = Join-Path $proj '.icongen\last_logcat.txt'
    & $adb -s $Serial logcat -d -t $LogLines 2>&1 | Out-File -FilePath $log -Encoding utf8
    Write-Host "logcat -> $log" -ForegroundColor Cyan
    & $adb -s $Serial logcat -d -t $LogLines 2>&1 |
        Select-String -Pattern "com.companion.chat|AndroidRuntime|FATAL EXCEPTION| E/|EmbedAcceptance" |
        Select-Object -Last 60
}

if ($ShotPath -ne "") {
    & $adb -s $Serial shell screencap -p /sdcard/_shot.png
    & $adb -s $Serial pull /sdcard/_shot.png $ShotPath | Out-Null
    Write-Host "screenshot -> $ShotPath" -ForegroundColor Cyan
}

Write-Host "== done (testExit=$testExit) ==" -ForegroundColor Green
exit $testExit
