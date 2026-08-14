[CmdletBinding()]
param(
    [string]$DataRoot
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($DataRoot)) {
    $codeRoot = Split-Path -Parent $PSScriptRoot
    $resolvedCodeRoot = (Resolve-Path -LiteralPath $codeRoot).Path
    $driveRoot = [System.IO.Path]::GetPathRoot($resolvedCodeRoot)
    if ([string]::IsNullOrWhiteSpace($driveRoot)) {
        throw "Cannot determine the drive root for $resolvedCodeRoot"
    }
    $DataRoot = Join-Path $driveRoot 'camera-safe-routing-data'
}

$resolvedDataRoot = [System.IO.Path]::GetFullPath($DataRoot)
$directories = @(
    $resolvedDataRoot,
    (Join-Path $resolvedDataRoot 'osm'),
    (Join-Path $resolvedDataRoot 'boundaries'),
    (Join-Path $resolvedDataRoot 'cameras'),
    (Join-Path $resolvedDataRoot 'graph-cache\jingjinji-compliant-time-v2'),
    (Join-Path $resolvedDataRoot 'snapshots'),
    (Join-Path $resolvedDataRoot 'downloads\cameras'),
    (Join-Path $resolvedDataRoot 'failed\cameras'),
    (Join-Path $resolvedDataRoot 'backups\cameras'),
    (Join-Path $resolvedDataRoot 'logs\archive')
)

foreach ($directory in $directories) {
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
}

[PSCustomObject]@{
    DataRoot = $resolvedDataRoot
    JingjinjiPbf = Join-Path $resolvedDataRoot 'osm\jingjinji-latest.osm.pbf'
    SixthRingBoundary = Join-Path $resolvedDataRoot 'boundaries\sixth-ring-boundary-jingjinji.geojson'
    CameraJson = Join-Path $resolvedDataRoot 'cameras\camera.json'
    CameraBackups = Join-Path $resolvedDataRoot 'backups\cameras'
    CameraFailures = Join-Path $resolvedDataRoot 'failed\cameras'
    GraphCache = Join-Path $resolvedDataRoot 'graph-cache\jingjinji-compliant-distance-v1'
    Snapshots = Join-Path $resolvedDataRoot 'snapshots'
    LogFile = Join-Path $resolvedDataRoot 'logs\camera-safe-routing-server.log'
} | Format-List
