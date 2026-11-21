# 陪聊 App 数据备份 / 恢复（真实数据保护）
#
# 背景（2026-11-20 事故）：跑 `connectedAndroidTest` 会让 AGP 自动卸载被测 App，
# 卸载会删掉 /data/data/com.companion.chat 整个目录 —— 会话、记忆、API Key 全没，
# 且无法恢复（Room 无备份，WAL 随库重建）。
#
# 所以：
#   1) 跑任何 instrumented 测试（connectedDebugAndroidTest / -RunTests）之前，先 -Backup
#   2) 测试跑完、重装 App 之后，用 -Restore 推回去
#   3) 平时想留快照也可以手动 -Backup
#
# 用法：
#   .\tools\app-data-backup.ps1 -Backup                       # 备份到 .backups\<时间戳>\
#   .\tools\app-data-backup.ps1 -List                          # 列出已有备份
#   .\tools\app-data-backup.ps1 -Restore -From 2026-11-20_1900 # 恢复指定备份（需 App 已安装）
#
# 说明：
# - 只处理 debug 包（run-as 需要 debuggable）。文件经 `adb exec-out` 原样二进制拉取，
#   恢复走 `adb shell` 的 stdin 重定向，不经过文本编码转换，二进制安全。
# - 恢复后必须**强杀并重启** App，否则 Room/SharedPreferences 内存缓存仍指向旧句柄。
param(
    [switch]$Backup,
    [switch]$Restore,
    [switch]$List,
    [string]$From = "",
    [string]$Serial = "dcfd02b4"
)

$ErrorActionPreference = 'Stop'
$pkg = 'com.companion.chat'
$adb = 'C:\ai-deepseek\phone-tools\platform-tools\adb.exe'
$proj = 'C:\Users\clc\.deepseek-project\dsh-project'
$root = Join-Path $proj '.backups'

# 需要备份的路径（相对 /data/data/<pkg>）
$targets = @(
    'databases',
    'shared_prefs',
    'files',
    'no_backup'
)

function Adb($adbArgs) {
    # ⚠️ 参数名不能叫 $args —— 那是 PowerShell 自动变量，会覆盖函数参数（曾导致静默拿不到文件）
    # adb 会往 stderr 打 "Installed as <path>"，一并滤掉
    & $adb -s $Serial @adbArgs 2>$null | Where-Object { $_ -and ($_.ToString() -notmatch 'Installed as') }
}

function Get-RemoteFiles([string]$dir) {
    # 列出目录下所有普通文件，只保留以该目录开头的真实路径
    $out = Adb @('shell', "run-as $pkg find $dir -type f 2>/dev/null")
    return @($out | ForEach-Object { $_.ToString().Trim() } |
        Where-Object { $_ -and $_ -match "^$([regex]::Escape($dir))/" })
}

if ($List) {
    if (-not (Test-Path $root)) { "no backups yet ($root)"; exit 0 }
    Get-ChildItem $root -Directory | Sort-Object Name -Descending | ForEach-Object {
        $n = (Get-ChildItem $_.FullName -Recurse -File | Measure-Object).Count
        "{0}  ({1} files)" -f $_.Name, $n
    }
    exit 0
}

if ($Backup) {
    $stamp = Get-Date -Format 'yyyy-MM-dd_HHmmss'
    $dest = Join-Path $root $stamp
    New-Item -ItemType Directory -Force -Path $dest | Out-Null

    $count = 0
    foreach ($t in $targets) {
        $files = Get-RemoteFiles $t
        foreach ($rel in $files) {
            $localPath = Join-Path $dest ($rel -replace '/', '\')
            New-Item -ItemType Directory -Force -Path (Split-Path $localPath) | Out-Null
            # exec-out 不做 LF 转换，二进制安全
            & cmd /c "`"$adb`" -s $Serial exec-out `"run-as $pkg cat $rel`" > `"$localPath`""
            if ((Get-Item $localPath).Length -gt 0) { $count++ } else { Remove-Item $localPath -Force }
        }
    }
    "backup -> $dest  ($count files)"
    # 关键文件存在性检查
    foreach ($k in @('databases\companion.db', 'shared_prefs\api_config_enc.xml')) {
        $p = Join-Path $dest $k
        if (Test-Path $p) { "  OK  $k  ($((Get-Item $p).Length) B)" } else { "  MISSING  $k" }
    }
    exit 0
}

if ($Restore) {
    if (-not $From) { throw "-Restore 需要 -From <备份目录名>" }
    $src = Join-Path $root $From
    if (-not (Test-Path $src)) { throw "备份不存在：$src" }
    if (-not (Adb @('shell', "pm path $pkg"))) { throw "App 未安装，先装再恢复" }

    $files = Get-ChildItem $src -Recurse -File
    $ok = 0
    foreach ($f in $files) {
        $rel = ($f.FullName.Substring($src.Length + 1) -replace '\\', '/')
        # 目录先建好；内容走 stdin 重定向（二进制安全）
        $parent = ($rel -replace '/[^/]+$', '')
        Adb @('shell', "run-as $pkg mkdir -p $parent") | Out-Null
        & cmd /c "`"$adb`" -s $Serial shell `"run-as $pkg sh -c 'cat > $rel'`" < `"$($f.FullName)`""
        $ok++
    }
    "restore <- $src  ($ok files)"
    Adb @('shell', "am force-stop $pkg") | Out-Null
    Adb @('shell', "am start -W -n $pkg/com.companion.chat.ui.MainActivity") | Out-Null
    "restarted $pkg"
    exit 0
}

"usage: -Backup | -Restore -From <dir> | -List"