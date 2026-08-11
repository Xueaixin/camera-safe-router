[CmdletBinding()]
param(
    # 数据根目录（默认当前盘 \camera-safe-routing-data）。
    [string]$DataRoot,
    # 后端 jar 路径（提供时额外执行 graph-check，校验缓存配置指纹与 jar 配套）。
    [string]$Jar,
    # java 可执行文件（默认 PATH 中的 java）。
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
$jarPath = Resolve-DefaultPath $Jar (Join-Path $resolvedCodeRoot 'server\target\camera-safe-routing-server-0.1.0-SNAPSHOT.jar')
$javaExecutable = if ([string]::IsNullOrWhiteSpace($Java)) { 'java' } else { $Java }

$pbf = Join-Path $resolvedDataRoot 'osm\jingjinji-latest.osm.pbf'
$boundary = Join-Path $resolvedDataRoot 'boundaries\sixth-ring-boundary.geojson'
$cache = Join-Path $resolvedDataRoot 'graph-cache\jingjinji-compliant-time-v2'
$cacheSource = Join-Path $cache 'camera-safe-source.sha256'
$cacheConfig = Join-Path $cache 'camera-safe-routing-config.sha256'
$cacheProps = Join-Path $cache 'properties.txt'

$failures = @()

foreach ($required in @($pbf, $boundary, $cache, $cacheSource, $cacheConfig, $cacheProps)) {
    if (-not (Test-Path -LiteralPath $required)) {
        $failures += "missing: $required"
    }
}

if ($failures.Count -eq 0) {
    $pbfHash = (Get-FileHash -LiteralPath $pbf -Algorithm SHA256).Hash.ToLowerInvariant()
    $cacheSourceHash = (Get-Content -LiteralPath $cacheSource -Raw).Trim().ToLowerInvariant()
    $boundaryJson = Get-Content -LiteralPath $boundary -Raw -Encoding UTF8 | ConvertFrom-Json
    $boundaryHash = ([string]$boundaryJson.sourcePbfSha256).ToLowerInvariant()
    $configHash = (Get-Content -LiteralPath $cacheConfig -Raw).Trim()
    $propsText = Get-Content -LiteralPath $cacheProps -Raw

    Write-Host "PBF SHA-256              : $pbfHash"
    Write-Host "cache source SHA-256     : $cacheSourceHash"
    Write-Host "boundary sourcePbfSha256 : $boundaryHash"
    Write-Host "cache config SHA-256     : $configHash"

    if ($pbfHash -ne $cacheSourceHash) { $failures += 'PBF hash does not match cache camera-safe-source.sha256' }
    if ($pbfHash -ne $boundaryHash) { $failures += 'PBF hash does not match boundary sourcePbfSha256' }
    if (-not ($propsText -match 'osm_way_id')) { $failures += 'cache missing osm_way_id encoded value' }
    if (-not ($propsText -match 'sixth_ring_mainline')) { $failures += 'cache missing sixth_ring_mainline encoded value' }

    if (-not [string]::IsNullOrWhiteSpace($Jar) -and $failures.Count -eq 0) {
        Write-Host 'Running graph-check against the jar to verify cache compatibility...'
        & $javaExecutable -jar $jarPath graph-check --pbf $pbf --cache $cache
        if ($LASTEXITCODE -ne 0) {
            $failures += 'graph-check failed: cache configuration fingerprint does not match the jar'
        }
    }
}

if ($failures.Count -gt 0) {
    Write-Host 'DATA CONSISTENCY: FAILED'
    $failures | ForEach-Object { Write-Host ('  - ' + $_) }
    exit 1
}
Write-Host 'DATA CONSISTENCY: OK'
