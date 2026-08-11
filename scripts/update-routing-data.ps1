[CmdletBinding()]
param(
    # 新的部署包 data.zip（更新模式必填）。
    [string]$Package,
    # 后端 server.jar。
    [string]$Jar,
    # 数据根目录（默认当前盘 \camera-safe-routing-data）。
    [string]$DataRoot,
    # 回滚模式：从最近的 routing 备份恢复 osm/boundaries/graph-cache。
    [switch]$Rollback,
    # 只准备数据，不重启服务。
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
$backupRoot = Join-Path $resolvedDataRoot 'backups\routing'
$javaExecutable = if ([string]::IsNullOrWhiteSpace($Java)) { 'java' } else { $Java }
$jarPath = if ([string]::IsNullOrWhiteSpace($Jar)) { $null } else { [System.IO.Path]::GetFullPath($Jar) }

function Stop-RunningServer {
    $procs = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object { $_.CommandLine -like '*camera-safe-routing-server*' }
    foreach ($p in $procs) {
        Write-Host ("Stopping PID " + $p.ProcessId)
        Stop-Process -Id $p.ProcessId -Force
    }
    Start-Sleep -Seconds 2
}

function Start-ServerAndWait {
    param([int]$Port)
    $env:ROUTING_DATA_ROOT = $resolvedDataRoot
    $env:SERVER_PORT = [string]$Port
    Start-Process -FilePath $javaExecutable -ArgumentList @('-jar', $jarPath) -WindowStyle Hidden | Out-Null
    Remove-Item Env:ROUTING_DATA_ROOT, Env:SERVER_PORT -ErrorAction SilentlyContinue
    $ready = $false
    for ($i = 0; $i -lt 60; $i++) {
        Start-Sleep -Seconds 10
        try {
            $r = Invoke-WebRequest -Uri "http://localhost:$Port/api/v1/readiness" -UseBasicParsing -TimeoutSec 5
            if (($r.Content | ConvertFrom-Json).status -eq 'READY') {
                $ready = $true
                break
            }
        } catch { }
    }
    if (-not $ready) {
        Write-Host 'Service not READY within 10 minutes. Check the log:'
        Write-Host "  $resolvedDataRoot\logs\camera-safe-routing-server.log"
        exit 1
    }
    Write-Host 'UPDATE OK: readiness READY'
}

function Verify-And-Start {
    param([int]$Port)
    if (-not [string]::IsNullOrWhiteSpace($Jar)) {
        & $PSScriptRoot\verify-data-consistency.ps1 -DataRoot $resolvedDataRoot -Jar $jarPath -Java $javaExecutable
        if ($LASTEXITCODE -ne 0) { throw 'data consistency check failed' }
    }
    if ($SkipStart) {
        Write-Host 'Data prepared; service not restarted (SkipStart).'
        return
    }
    Stop-RunningServer
    Start-ServerAndWait -Port $Port
}

if ($Rollback) {
    $backups = @(Get-ChildItem -LiteralPath $backupRoot -Directory -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending)
    if ($backups.Count -eq 0) { throw "no routing backup found under $backupRoot" }
    $latest = $backups[0]
    Write-Host "Rolling back to $($latest.Name)"
    foreach ($sub in @('osm', 'boundaries', 'graph-cache')) {
        $source = Join-Path $latest.FullName $sub
        if (-not (Test-Path -LiteralPath $source)) { continue }
        $target = Join-Path $resolvedDataRoot $sub
        New-Item -ItemType Directory -Path $target -Force | Out-Null
        Copy-Item -Path (Join-Path $source '*') -Destination $target -Recurse -Force
    }
    Verify-And-Start -Port 8080
    exit 0
}

if (-not (Test-Path -LiteralPath $Package -PathType Leaf)) { throw "Package not found: $Package" }

# ---- 1. 备份当前路网相关目录 ----
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backupDir = Join-Path $backupRoot $timestamp
foreach ($sub in @('osm', 'boundaries', 'graph-cache')) {
    $source = Join-Path $resolvedDataRoot $sub
    if (Test-Path -LiteralPath $source) {
        $target = Join-Path $backupDir $sub
        New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
        Copy-Item -Path (Join-Path $source '*') -Destination $target -Recurse -Force
    }
}
Write-Host "Backup -> $backupDir"

# ---- 2. 解压并替换 ----
$tempExtract = Join-Path $env:TEMP ("routing-update-" + [guid]::NewGuid().ToString('N'))
Expand-Archive -LiteralPath $Package -DestinationPath $tempExtract -Force
foreach ($sub in @('osm', 'boundaries', 'graph-cache')) {
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

# ---- 3. 校验并重启 ----
Verify-And-Start -Port 8080
