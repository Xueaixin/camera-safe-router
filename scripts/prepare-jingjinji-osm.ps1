[CmdletBinding()]
param(
    [string]$DataRoot,
    [string]$WorkspaceRoot,
    [string]$SourcePath,
    [string]$OsmiumPath,
    [string]$OutputPath,
    [string]$Bounds = '113.0,35.5,120.5,43.0',
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

$codeRoot = Split-Path -Parent $PSScriptRoot
$resolvedCodeRoot = (Resolve-Path -LiteralPath $codeRoot).Path
$projectRoot = Split-Path -Parent $resolvedCodeRoot

function Invoke-Osmium {
    param(
        [Parameter(Mandatory)]
        [string[]]$Arguments
    )

    & $script:OsmiumExecutable @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "osmium failed with exit code $LASTEXITCODE`: $($Arguments -join ' ')"
    }
}

function Invoke-OsmiumJson {
    param(
        [Parameter(Mandatory)]
        [string[]]$Arguments
    )

    $output = & $script:OsmiumExecutable @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "osmium failed with exit code $LASTEXITCODE`: $($Arguments -join ' ')"
    }
    return (($output -join [Environment]::NewLine) | ConvertFrom-Json)
}

if ([string]::IsNullOrWhiteSpace($DataRoot)) {
    $driveRoot = [System.IO.Path]::GetPathRoot($resolvedCodeRoot)
    if ([string]::IsNullOrWhiteSpace($driveRoot)) {
        throw "Cannot determine the drive root for $resolvedCodeRoot"
    }
    $DataRoot = Join-Path $driveRoot 'camera-safe-routing-data'
}

$resolvedDataRoot = [System.IO.Path]::GetFullPath($DataRoot)
if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Join-Path $projectRoot 'workspace'
}
$resolvedWorkspaceRoot = [System.IO.Path]::GetFullPath($WorkspaceRoot)
$boundValues = @($Bounds.Split(',') | ForEach-Object {
    [double]::Parse($_.Trim(), [System.Globalization.CultureInfo]::InvariantCulture)
})
if ($boundValues.Count -ne 4) {
    throw 'Bounds must contain west,south,east,north.'
}
if ($boundValues[0] -ge $boundValues[2] -or $boundValues[1] -ge $boundValues[3]) {
    throw 'Bounds must satisfy west < east and south < north.'
}

if ([string]::IsNullOrWhiteSpace($SourcePath)) {
    $SourcePath = Join-Path $resolvedWorkspaceRoot 'downloads\osm\china-latest.osm.pbf'
}
if ([string]::IsNullOrWhiteSpace($OsmiumPath)) {
    $OsmiumPath = Join-Path $resolvedWorkspaceRoot 'tools\osmium-env\Library\bin\osmium.exe'
}

$resolvedSourcePath = [System.IO.Path]::GetFullPath($SourcePath)
$script:OsmiumExecutable = [System.IO.Path]::GetFullPath($OsmiumPath)

if (-not (Test-Path -LiteralPath $resolvedSourcePath -PathType Leaf)) {
    throw "China PBF does not exist: $resolvedSourcePath"
}
if (-not (Test-Path -LiteralPath $script:OsmiumExecutable -PathType Leaf)) {
    throw "osmium-tool does not exist: $script:OsmiumExecutable"
}

$sourceInfo = Invoke-OsmiumJson -Arguments @('fileinfo', '-j', $resolvedSourcePath)
$sourceHash = (Get-FileHash -LiteralPath $resolvedSourcePath -Algorithm SHA256).Hash.ToLowerInvariant()
$sourceTimestamp = $sourceInfo.header.option.timestamp
if ([string]::IsNullOrWhiteSpace($sourceTimestamp)) {
    $sourceDate = (Get-Item -LiteralPath $resolvedSourcePath).LastWriteTimeUtc.ToString('yyyyMMdd')
} else {
    $sourceDate = [DateTimeOffset]::Parse($sourceTimestamp).UtcDateTime.ToString('yyyyMMdd')
}

if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $outputDirectory = Join-Path $resolvedWorkspaceRoot 'work\osm'
    $OutputPath = Join-Path $outputDirectory "jingjinji-smart-$sourceDate-$($sourceHash.Substring(0, 12)).osm.pbf"
}

$resolvedOutputPath = [System.IO.Path]::GetFullPath($OutputPath)
$outputDirectory = Split-Path -Parent $resolvedOutputPath
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
$outputStem = ([System.IO.Path]::GetFileName($resolvedOutputPath) -replace '\.osm\.pbf$', '')
$restrictionPath = Join-Path $outputDirectory "$outputStem-restrictions-check.osm.pbf"

if ((Test-Path -LiteralPath $resolvedOutputPath -PathType Leaf) -and -not $Force) {
    $existingInfo = Invoke-OsmiumJson -Arguments @('fileinfo', '--json', $resolvedOutputPath)
    $existingBounds = @($existingInfo.header.boxes[0])
    $boundsMatch = $existingBounds.Count -eq 4
    for ($index = 0; $boundsMatch -and $index -lt 4; $index++) {
        $boundsMatch = [Math]::Abs([double]$existingBounds[$index] - $boundValues[$index]) -lt 0.0000001
    }
    if (-not $boundsMatch) {
        throw "Existing extract bounds do not match $Bounds. Use a different OutputPath or pass -Force."
    }
    Write-Host "Reusing existing extract: $resolvedOutputPath"
    Invoke-Osmium -Arguments @('check-refs', '--no-progress', $resolvedOutputPath)

    if (-not (Test-Path -LiteralPath $restrictionPath -PathType Leaf)) {
        $temporaryRestriction = "$restrictionPath.partial-$PID.osm.pbf"
        try {
            Invoke-Osmium -Arguments @(
                'tags-filter',
                '--output-format', 'pbf',
                '--output', $temporaryRestriction,
                '--no-progress',
                $resolvedOutputPath,
                'r/type=restriction'
            )
            Invoke-Osmium -Arguments @('check-refs', '--check-relations', '--no-progress', $temporaryRestriction)
            Move-Item -LiteralPath $temporaryRestriction -Destination $restrictionPath -Force
        } finally {
            if (Test-Path -LiteralPath $temporaryRestriction -PathType Leaf) {
                Remove-Item -LiteralPath $temporaryRestriction -Force
            }
        }
    } else {
        Invoke-Osmium -Arguments @('check-refs', '--check-relations', '--no-progress', $restrictionPath)
    }
} else {
    $temporaryOutput = "$resolvedOutputPath.partial-$PID.osm.pbf"
    $temporaryRestriction = "$restrictionPath.partial-$PID.osm.pbf"
    try {
        Invoke-Osmium -Arguments @(
            'extract',
            '--bbox', $Bounds,
            '--strategy', 'smart',
            '--set-bounds',
            '--generator', 'camera-safe-router/osmium-tool-1.19.1-smart',
            '--output-format', 'pbf',
            '--output', $temporaryOutput,
            '--no-progress',
            $resolvedSourcePath
        )
        Invoke-Osmium -Arguments @('check-refs', '--no-progress', $temporaryOutput)
        Invoke-Osmium -Arguments @(
            'tags-filter',
            '--output-format', 'pbf',
            '--output', $temporaryRestriction,
            '--no-progress',
            $temporaryOutput,
            'r/type=restriction'
        )
        Invoke-Osmium -Arguments @('check-refs', '--check-relations', '--no-progress', $temporaryRestriction)

        Move-Item -LiteralPath $temporaryRestriction -Destination $restrictionPath -Force
        Move-Item -LiteralPath $temporaryOutput -Destination $resolvedOutputPath -Force
    } finally {
        if (Test-Path -LiteralPath $temporaryOutput -PathType Leaf) {
            Remove-Item -LiteralPath $temporaryOutput -Force
        }
        if (Test-Path -LiteralPath $temporaryRestriction -PathType Leaf) {
            Remove-Item -LiteralPath $temporaryRestriction -Force
        }
    }
}

$extractInfo = Invoke-OsmiumJson -Arguments @('fileinfo', '--extended', '--json', '--no-crc', $resolvedOutputPath)
$extractHash = (Get-FileHash -LiteralPath $resolvedOutputPath -Algorithm SHA256).Hash.ToLowerInvariant()

[PSCustomObject]@{
    DataRoot = $resolvedDataRoot
    WorkspaceRoot = $resolvedWorkspaceRoot
    SourcePath = $resolvedSourcePath
    SourceTimestamp = $sourceTimestamp
    SourceSha256 = $sourceHash
    Bounds = $Bounds
    Strategy = 'smart'
    OutputPath = $resolvedOutputPath
    OutputSha256 = $extractHash
    OutputBytes = $extractInfo.file.size
    Nodes = $extractInfo.data.count.nodes
    Ways = $extractInfo.data.count.ways
    Relations = $extractInfo.data.count.relations
    RestrictionCheckPath = $restrictionPath
} | Format-List
