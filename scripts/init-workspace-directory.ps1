[CmdletBinding()]
param(
    [string]$WorkspaceRoot
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $codeRoot = Split-Path -Parent $PSScriptRoot
    $resolvedCodeRoot = (Resolve-Path -LiteralPath $codeRoot).Path
    $WorkspaceRoot = Join-Path (Split-Path -Parent $resolvedCodeRoot) 'workspace'
}

$resolvedWorkspaceRoot = [System.IO.Path]::GetFullPath($WorkspaceRoot)
$directories = @(
    $resolvedWorkspaceRoot,
    (Join-Path $resolvedWorkspaceRoot 'downloads\osm'),
    (Join-Path $resolvedWorkspaceRoot 'tools'),
    (Join-Path $resolvedWorkspaceRoot 'work\osm'),
    (Join-Path $resolvedWorkspaceRoot 'work\graph-cache-candidates'),
    (Join-Path $resolvedWorkspaceRoot 'work\sixth-ring'),
    (Join-Path $resolvedWorkspaceRoot 'reports'),
    (Join-Path $resolvedWorkspaceRoot 'reviews'),
    (Join-Path $resolvedWorkspaceRoot 'archive')
)

foreach ($directory in $directories) {
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
}

[PSCustomObject]@{
    WorkspaceRoot = $resolvedWorkspaceRoot
    ChinaPbf = Join-Path $resolvedWorkspaceRoot 'downloads\osm\china-latest.osm.pbf'
    Osmium = Join-Path $resolvedWorkspaceRoot 'tools\osmium-env\Library\bin\osmium.exe'
    OsmWork = Join-Path $resolvedWorkspaceRoot 'work\osm'
    GraphCacheCandidates = Join-Path $resolvedWorkspaceRoot 'work\graph-cache-candidates'
    SixthRingWork = Join-Path $resolvedWorkspaceRoot 'work\sixth-ring'
    Reports = Join-Path $resolvedWorkspaceRoot 'reports'
    Reviews = Join-Path $resolvedWorkspaceRoot 'reviews'
    Archive = Join-Path $resolvedWorkspaceRoot 'archive'
} | Format-List
