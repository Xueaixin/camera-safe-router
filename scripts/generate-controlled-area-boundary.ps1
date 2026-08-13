[CmdletBinding()]
param(
    # 六环线 GeoJSON（由 osmium 从 PBF 按 relation r295982 导出，LineString 要素）。
    [string]$SixthRingLines,
    # 通州行政区 GeoJSON（由 osmium 从 PBF 按 relation r2988902 导出）。
    [string]$TongzhouGeojson,
    # 源 PBF（用于计算 sourcePbfSha256）。
    [string]$Pbf,
    # 生成的 schema v4 边界输出路径（含 controlled_area/sixth_ring_area/tongzhou_area/provincial_border）。
    [string]$Output,
    # 生成的 Markdown 报告输出路径。
    [string]$Report,
    # 正式数据根目录（默认当前盘 \camera-safe-routing-data）。
    [string]$DataRoot,
    # 后端 jar 路径（默认 code\server\target\camera-safe-routing-server-0.1.0-SNAPSHOT.jar）。
    [string]$Jar,
    # java 可执行文件（默认 PATH 中的 java）。
    [string]$Java,
    # 生成批准版本（approvedForProduction=true）并发布到正式 boundaries 目录。
    [switch]$Publish,
    # 发布时跳过确认。
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

function Resolve-DefaultPath {
    param([string]$Path, [string]$Default)
    if ([string]::IsNullOrWhiteSpace($Path)) { return $Default }
    return $Path
}

# ---- 默认路径推导（与 init-data-directory.ps1 / init-workspace-directory.ps1 一致）----
$codeRoot = Split-Path -Parent $PSScriptRoot
$resolvedCodeRoot = (Resolve-Path -LiteralPath $codeRoot).Path
$driveRoot = [System.IO.Path]::GetPathRoot($resolvedCodeRoot)
if ([string]::IsNullOrWhiteSpace($driveRoot)) {
    throw "Cannot determine the drive root for $resolvedCodeRoot"
}
$resolvedDataRoot = [System.IO.Path]::GetFullPath((Resolve-DefaultPath $DataRoot (Join-Path $driveRoot 'camera-safe-routing-data')))
$workspaceRoot = [System.IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $resolvedCodeRoot) 'workspace'))
$controlledAreaDir = Join-Path $workspaceRoot 'work\controlled-area-v1'

$sixthRingLines = Resolve-DefaultPath $SixthRingLines (Join-Path $workspaceRoot 'work\sixth-ring\sixth-ring-lines.geojson')
$tongzhouInput = Resolve-DefaultPath $TongzhouGeojson (Join-Path $controlledAreaDir 'tongzhou-boundary.geojson')
$pbfInput = Resolve-DefaultPath $Pbf (Join-Path $resolvedDataRoot 'osm\jingjinji-latest.osm.pbf')
$outputPath = Resolve-DefaultPath $Output (Join-Path $controlledAreaDir 'controlled-area-boundary-candidate.geojson')
$reportPath = Resolve-DefaultPath $Report (Join-Path $controlledAreaDir 'controlled-area-boundary-report.md')
$jarPath = Resolve-DefaultPath $Jar (Join-Path $resolvedCodeRoot 'server\target\camera-safe-routing-server-0.1.0-SNAPSHOT.jar')
$javaExecutable = if ([string]::IsNullOrWhiteSpace($Java)) { 'java' } else { $Java }

foreach ($required in @($sixthRingLines, $tongzhouInput, $pbfInput, $jarPath)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Input not found: $required"
    }
}

Write-Host "SixthRingLines  : $sixthRingLines"
Write-Host "TongzhouGeojson : $tongzhouInput"
Write-Host "Pbf             : $pbfInput"
Write-Host "Output          : $outputPath"
Write-Host "Report          : $reportPath"

# ---- 调用 jar 内置 boundary-generate CLI ----
$cliArgs = @(
    'boundary-generate',
    '--sixth-ring-lines', $sixthRingLines,
    '--tongzhou', $tongzhouInput,
    '--pbf', $pbfInput,
    '--output', $outputPath
)
if (-not [string]::IsNullOrWhiteSpace($reportPath)) {
    $cliArgs += '--report', $reportPath
}
if ($Publish) {
    $cliArgs += '--approved'
}

& $javaExecutable -jar $jarPath @cliArgs
if ($LASTEXITCODE -ne 0) {
    throw "boundary-generate failed (exit $LASTEXITCODE)"
}

if (-not (Test-Path -LiteralPath $outputPath)) {
    throw "candidate not generated: $outputPath"
}
Write-Host "CANDIDATE=$outputPath"
Write-Host "REPORT=$reportPath"

# ---- 发布：审阅通过后复制到正式目录 ----
    if ($Publish) {
        $formalBoundary = Join-Path $resolvedDataRoot 'boundaries\sixth-ring-boundary.geojson'
        if (-not $Force) {
            $answer = Read-Host "Publish $outputPath to $formalBoundary? (y/N)"
            if ($answer -notin @('y', 'Y')) { Write-Host 'Publish cancelled.'; exit 0 }
        }
        New-Item -ItemType Directory -Path (Split-Path -Parent $formalBoundary) -Force | Out-Null
        Copy-Item -LiteralPath $outputPath -Destination $formalBoundary -Force
        Write-Host "Formal boundary updated: $formalBoundary (approvedForProduction=true)"
    }
