[CmdletBinding()]
param(
    # 六环边界输入：可以是旧格式（含 inside_boundary + sourcePbfSha256），
    # 也可以是 schema v3 并集边界（脚本自动取 sixth_ring_area 转成测试输入）。
    [string]$SixthRingBoundary,
    # 通州行政区 GeoJSON（从主 PBF 按 relation r2988902 提取，osmium 导出）。
    [string]$TongzhouGeojson,
    # 生成的 schema v3 候选边界输出路径。
    [string]$Output,
    # 生成的 Markdown 报告输出路径。
    [string]$Report,
    # 正式数据根目录（默认当前盘 \camera-safe-routing-data）。
    [string]$DataRoot,
    # Maven 本地仓库（可选；缺省使用 IDEA/系统默认仓库）。
    [string]$MavenLocalRepo,
    # 审阅通过后发布到正式目录（复制 + approvedForProduction=true）。
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

$sixthInput = Resolve-DefaultPath $SixthRingBoundary (Join-Path $resolvedDataRoot 'boundaries\sixth-ring-boundary.geojson')
$tongzhouInput = Resolve-DefaultPath $TongzhouGeojson (Join-Path $controlledAreaDir 'tongzhou-boundary.geojson')
$outputPath = Resolve-DefaultPath $Output (Join-Path $controlledAreaDir 'controlled-area-boundary-candidate.geojson')
$reportPath = Resolve-DefaultPath $Report (Join-Path $controlledAreaDir 'controlled-area-boundary-report.md')
foreach ($required in @($sixthInput, $tongzhouInput)) {
    if (-not (Test-Path -LiteralPath $required)) {
        throw "Input not found: $required"
    }
}
$outputParent = Split-Path -Parent $outputPath
$reportParent = Split-Path -Parent $reportPath
if (-not (Test-Path -LiteralPath $outputParent)) { New-Item -ItemType Directory -Path $outputParent -Force | Out-Null }
if (-not (Test-Path -LiteralPath $reportParent)) { New-Item -ItemType Directory -Path $reportParent -Force | Out-Null }

# ---- 准备测试输入：v3 边界自动转成 inside_boundary 输入 ----
$tempSixthInput = Join-Path $controlledAreaDir '.work\sixth-ring-inside-input.geojson'
New-Item -ItemType Directory -Path (Split-Path -Parent $tempSixthInput) -Force | Out-Null
$env:RHT_SRC = $sixthInput
$env:RHT_DST = $tempSixthInput
$env:RHT_PBF = Join-Path $resolvedDataRoot 'osm\jingjinji-latest.osm.pbf'
@'
const fs = require('fs');
const src = process.env.RHT_SRC;
const dst = process.env.RHT_DST;
const root = JSON.parse(fs.readFileSync(src, 'utf8'));
const features = root.features || [];
const inside = features.find(f => f.properties && f.properties.role === 'inside_boundary');
if (inside) {
  const converted = {
    type: 'FeatureCollection',
    coordinateSystem: 'WGS84',
    sourcePbfSha256: root.sourcePbfSha256
  };
  if (!/^[0-9a-fA-F]{64}$/.test(String(converted.sourcePbfSha256 || ''))) {
    const fs2 = require('fs');
    const crypto = require('crypto');
    const hash = crypto.createHash('sha256').update(fs2.readFileSync(process.env.RHT_PBF)).digest('hex');
    converted.sourcePbfSha256 = hash;
  }
  converted.features = features.map(f => ({
    type: 'Feature',
    properties: { role: f.properties.role },
    geometry: f.geometry
  }));
  fs.writeFileSync(dst, JSON.stringify(converted), 'utf8');
} else {
  const area = features.find(f => f.properties && f.properties.role === 'sixth_ring_area');
  if (!area) throw new Error('sixth-ring input has neither inside_boundary nor sixth_ring_area: ' + src);
  const sourcePbf = root.sourcePbfSha256;
  if (!/^[0-9a-fA-F]{64}$/.test(String(sourcePbf))) throw new Error('missing sourcePbfSha256: ' + src);
  const converted = {
    type: 'FeatureCollection',
    coordinateSystem: 'WGS84',
    sourcePbfSha256: String(sourcePbf),
    features: [{ type: 'Feature', properties: { role: 'inside_boundary' }, geometry: area.geometry }]
  };
  fs.writeFileSync(dst, JSON.stringify(converted), 'utf8');
}
console.log('SIXTH_RING_INPUT=' + dst);
'@ | node -
if ($LASTEXITCODE -ne 0) { throw 'failed to prepare sixth-ring input' }

Write-Host "SixthRingBoundary : $sixthInput"
Write-Host "TongzhouGeojson   : $tongzhouInput"
Write-Host "Output            : $outputPath"
Write-Host "Report            : $reportPath"

# ---- 运行并集边界生成测试 ----
$serverDir = Join-Path $resolvedCodeRoot 'server'
Push-Location $serverDir
try {
    $mvnArgs = @()
    if (-not [string]::IsNullOrWhiteSpace($MavenLocalRepo)) {
        $mvnArgs += "-Dmaven.repo.local=$MavenLocalRepo"
    }
    $mvnArgs += "-Dcontrolled.area.sixth-ring-boundary=$tempSixthInput"
    $mvnArgs += "-Dcontrolled.area.tongzhou-geojson=$tongzhouInput"
    $mvnArgs += "-Dcontrolled.area.output=$outputPath"
    $mvnArgs += "-Dcontrolled.area.report=$reportPath"
    $mvnArgs += "-Dtest=ControlledAreaBoundaryPocTest"
    $mvnArgs += 'test'
    & mvn @mvnArgs
    if ($LASTEXITCODE -ne 0) { throw "boundary generation test failed (exit $LASTEXITCODE)" }
} finally {
    Pop-Location
}

if (-not (Test-Path -LiteralPath $outputPath)) { throw "candidate not generated: $outputPath" }
Write-Host "CANDIDATE=$outputPath"
Write-Host "REPORT=$reportPath"

# ---- 清理临时输入 ----
$tempWorkDir = Split-Path -Parent $tempSixthInput
if (Test-Path -LiteralPath $tempSixthInput) { Remove-Item -LiteralPath $tempSixthInput -Force }
if ((Test-Path -LiteralPath $tempWorkDir) -and -not (Get-ChildItem -LiteralPath $tempWorkDir -Force)) {
    Remove-Item -LiteralPath $tempWorkDir -Force
}

# ---- 可选发布：审阅通过后复制到正式目录并置 approvedForProduction=true ----
if ($Publish) {
    $formalBoundary = Join-Path $resolvedDataRoot 'boundaries\sixth-ring-boundary.geojson'
    if (-not $Force) {
        $answer = Read-Host "Publish $outputPath to $formalBoundary and mark approvedForProduction=true? (y/N)"
        if ($answer -notin @('y', 'Y')) { Write-Host 'Publish cancelled.'; exit 0 }
    }
    $env:RHT_SRC = $outputPath
    $env:RHT_DST = $formalBoundary
    @'
const fs = require('fs');
const src = process.env.RHT_SRC;
const dst = process.env.RHT_DST;
let text = fs.readFileSync(src, 'utf8');
const replaced = text.replace(/("approvedForProduction"\s*:\s*)false/, '$1true');
if (replaced === text) throw new Error('approvedForProduction=false not found in ' + src);
fs.writeFileSync(dst, replaced, 'utf8');
console.log('PUBLISHED=' + dst);
'@ | node -
    if ($LASTEXITCODE -ne 0) { throw 'publish failed' }
    Write-Host "Formal boundary updated: $formalBoundary (approvedForProduction=true)"
}
