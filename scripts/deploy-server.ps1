[CmdletBinding()]
param(
    # 部署包 data.zip（必填）。
    [string]$Package,
    # 后端 server.jar（必填）。
    [string]$Jar,
    # 数据根目录（默认当前盘 \camera-safe-routing-data）。
    [string]$DataRoot,
    # HTTP 端口。
    [int]$ServerPort = 8080,
    # 开启摄像头定时自动更新（默认关闭）。
    [switch]$CameraUpdateEnabled,
    # 只准备数据，不启动服务。
    [switch]$SkipStart,
    # java 可执行文件。
    [string]$Java
)

$ErrorActionPreference = 'Stop'

function Resolve-DefaultPath {
    param([string]$Path, [string]$Default)
    if ([string]::IsNullOrWhiteSpace($Path)) { return $Default }
    return $Path
}

$codeRoot = Split-Path -Parent $PSScriptRoot
$resolvedCodeRoot = (Resolve-Path -LiteralPath $codeRoot).Path
$driveRoot = [System.IO.Path]::GetPathRoot($resolvedCodeRoot)
$resolvedDataRoot = [System.IO.Path]::GetFullPath((Resolve-DefaultPath $DataRoot (Join-Path $driveRoot 'camera-safe-routing-data')))
$jarPath = [System.IO.Path]::GetFullPath($Jar)
$javaExecutable = if ([string]::IsNullOrWhiteSpace($Java)) { 'java' } else { $Java }

if (-not (Test-Path -LiteralPath $Package -PathType Leaf)) { throw "Package not found: $Package" }
if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) { throw "Jar not found: $jarPath" }

Write-Host "DataRoot : $resolvedDataRoot"
Write-Host "Package  : $Package"
Write-Host "Jar      : $jarPath"

# ---- 1. 初始化目录 ----
& $PSScriptRoot\init-data-directory.ps1 -DataRoot $resolvedDataRoot | Out-Null

# ---- 2. 解压到临时目录并复制到数据根 ----
$tempExtract = Join-Path $env:TEMP ("routing-deploy-" + [guid]::NewGuid().ToString('N'))
Expand-Archive -LiteralPath $Package -DestinationPath $tempExtract -Force
foreach ($sub in @('osm', 'cameras', 'boundaries', 'graph-cache')) {
    $source = Join-Path $tempExtract $sub
    if (-not (Test-Path -LiteralPath $source)) {
        Remove-Item -LiteralPath $tempExtract -Recurse -Force
        throw "package missing required directory: $sub"
    }
    $target = Join-Path $resolvedDataRoot $sub
    New-Item -ItemType Directory -Path $target -Force | Out-Null
    Copy-Item -Path (Join-Path $source '*') -Destination $target -Recurse -Force
}
Remove-Item -LiteralPath $tempExtract -Recurse -Force

# ---- 3. 一致性校验 ----
& $PSScriptRoot\verify-data-consistency.ps1 -DataRoot $resolvedDataRoot -Jar $jarPath -Java $javaExecutable
if ($LASTEXITCODE -ne 0) { throw 'data consistency check failed' }

if ($SkipStart) {
    Write-Host 'Data prepared; service not started (SkipStart). Manual start:'
    Write-Host "  `$env:ROUTING_DATA_ROOT='$resolvedDataRoot'"
    Write-Host "  java -jar $jarPath"
    exit 0
}

# ---- 4. 启动并等待就绪 ----
$env:ROUTING_DATA_ROOT = $resolvedDataRoot
$env:SERVER_PORT = [string]$ServerPort
$env:CAMERA_UPDATE_ENABLED = if ($CameraUpdateEnabled) { 'true' } else { 'false' }
Start-Process -FilePath $javaExecutable -ArgumentList @('-jar', $jarPath) -WindowStyle Hidden | Out-Null
Remove-Item Env:ROUTING_DATA_ROOT, Env:SERVER_PORT, Env:CAMERA_UPDATE_ENABLED -ErrorAction SilentlyContinue

$ready = $false
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Seconds 10
    try {
        $r = Invoke-WebRequest -Uri "http://localhost:$ServerPort/api/v1/readiness" -UseBasicParsing -TimeoutSec 5
        if (($r.Content | ConvertFrom-Json).status -eq 'READY') {
            $ready = $true
            break
        }
    } catch { }
}
if (-not $ready) {
    Write-Host 'Service started but not READY within 10 minutes. Check the log:'
    Write-Host "  $resolvedDataRoot\logs\camera-safe-routing-server.log"
    exit 1
}
Write-Host "DEPLOY OK: readiness READY on port $ServerPort, data root $resolvedDataRoot"
