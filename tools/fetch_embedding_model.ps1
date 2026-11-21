# 下载 bge-small-zh-v1.5 ONNX 模型 + 词表到 assets/models。任务 2.1/2.3
# 用法：pwsh -File tools/fetch_embedding_model.ps1   （在项目根目录执行）
# 走 hf-mirror 镜像；下载后校验字节数与 SHA256。已在仓内时加 -Force 覆盖。
param(
    [switch]$Force
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$dir  = Join-Path $root 'app\src\main\assets\models'
New-Item -ItemType Directory -Force -Path $dir | Out-Null

$mirror = 'https://hf-mirror.com/Xenova/bge-small-zh-v1.5/resolve/main'
$files = @(
    @{ name = 'vocab.txt';                url = "$mirror/vocab.txt";         size = 109540;   sha = '45BBAC6B341C319ADC98A532532882E91A9CEFC0329AA57BAC9AE761C27B291C' },
    @{ name = 'bge-small-zh-v1.5.onnx';   url = "$mirror/onnx/model.onnx";   size = 94851877; sha = '69A0B846F4F116B5E6AABF9546EA6754D02264F3211A13A1BD69B31B8040749A' }
)

foreach ($f in $files) {
    $dst = Join-Path $dir $f.name
    if ((Test-Path $dst) -and -not $Force) {
        $sz = (Get-Item $dst).Length
        if ($sz -eq $f.size) { Write-Output "skip $($f.name) (already present, $sz bytes)"; continue }
    }
    $tmp = "$dst.part"
    Write-Output "downloading $($f.name) ..."
    & curl.exe -L --max-time 1800 -o $tmp $f.url
    if ($LASTEXITCODE -ne 0) { throw "curl failed for $($f.name) (exit $LASTEXITCODE)" }
    $sz = (Get-Item $tmp).Length
    if ($sz -ne $f.size) { throw "$($f.name) size mismatch: got $sz expected $($f.size)" }
    $h = (Get-FileHash $tmp -Algorithm SHA256).Hash
    if ($h -ne $f.sha) { throw "$($f.name) sha256 mismatch: $h" }
    Move-Item -Force $tmp $dst
    Write-Output "OK -> $dst"
}
Write-Output 'done.'
