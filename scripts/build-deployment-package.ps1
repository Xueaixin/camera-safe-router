[CmdletBinding()]
param(
    # 中国 PBF（历史京津冀流程使用；正式北京方案请直接传 -Pbf）。
    [string]$ChinaPbf,
    # 北京 PBF（正式方案直接传 beijing-latest.osm.pbf）。
    [string]$Pbf,
    # 初版 camera.json（必填，打包进 data.zip）。
    [string]$CameraJson,
    # 输出目录（jar + data.zip），默认 workspace\work\deployment-staging\<时间戳>。
    [string]$OutDir,
    # 暂存数据根（打包内容来源），默认输出目录下 camera-safe-routing-data。
    [string]$DataRoot,
    # 工作区根目录。
    [string]$WorkspaceRoot,
    # 后端 jar 路径。
    [string]$Jar,
    # osmium 可执行文件。
    [string]$OsmiumPath,
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
$workspaceRoot = [System.IO.Path]::GetFullPath((Resolve-DefaultPath $WorkspaceRoot (Join-Path (Split-Path -Parent $resolvedCodeRoot) 'workspace')))
$pkgTimestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$pkgDir = Resolve-DefaultPath $OutDir (Join-Path $workspaceRoot "work\deployment-staging\pkg-$pkgTimestamp")
$resolvedPkgDir = [System.IO.Path]::GetFullPath($pkgDir)
$stagingRoot = [System.IO.Path]::GetFullPath((Resolve-DefaultPath $DataRoot (Join-Path $resolvedPkgDir 'camera-safe-routing-data')))
$tmpDir = Join-Path $resolvedPkgDir 'tmp'
$jarPath = [System.IO.Path]::GetFullPath((Resolve-DefaultPath $Jar (Join-Path $resolvedCodeRoot 'server\target\camera-safe-routing-server-0.1.0-SNAPSHOT.jar')))
$osmiumPath = [System.IO.Path]::GetFullPath((Resolve-DefaultPath $OsmiumPath (Join-Path $workspaceRoot 'tools\osmium-env\Library\bin\osmium.exe')))
$javaExecutable = if ([string]::IsNullOrWhiteSpace($Java)) { 'java' } else { $Java }

if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
    throw "jar not found: $jarPath"
}
if (-not (Test-Path -LiteralPath $osmiumPath -PathType Leaf)) {
    throw "osmium-tool does not exist: $osmiumPath`n安装方法：运行 .\scripts\install-osmium.ps1 用工作区 micromamba 创建，或从已有机器拷贝 workspace\tools\osmium-env"
}
if ([string]::IsNullOrWhiteSpace($CameraJson) -or -not (Test-Path -LiteralPath $CameraJson -PathType Leaf)) {
    throw 'CameraJson is required and must exist'
}

New-Item -ItemType Directory -Path $stagingRoot -Force | Out-Null
New-Item -ItemType Directory -Path $tmpDir -Force | Out-Null
& $PSScriptRoot\init-data-directory.ps1 -DataRoot $stagingRoot | Out-Null

Write-Host "Package dir   : $resolvedPkgDir"
Write-Host "Staging root  : $stagingRoot"
Write-Host "Jar           : $jarPath"
Write-Host "Osmium        : $osmiumPath"

# ---- 1. 确定并发布 PBF 到暂存数据根 ----
$resolvedPbf = $null
if (-not [string]::IsNullOrWhiteSpace($Pbf)) {
    $resolvedPbf = [System.IO.Path]::GetFullPath($Pbf)
    if (-not (Test-Path -LiteralPath $resolvedPbf -PathType Leaf)) {
        throw "Pbf not found: $resolvedPbf"
    }
} elseif (-not [string]::IsNullOrWhiteSpace($ChinaPbf)) {
    & $PSScriptRoot\prepare-jingjinji-osm.ps1 -SourcePath $ChinaPbf -DataRoot $stagingRoot -WorkspaceRoot $workspaceRoot -OsmiumPath $osmiumPath
    if ($LASTEXITCODE -ne 0) { throw 'PBF slicing failed' }
    $sliced = Get-ChildItem (Join-Path $workspaceRoot 'work\osm') -Filter 'jingjinji-smart-*.osm.pbf' |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $sliced) { throw 'no sliced jingjinji-smart-*.osm.pbf produced' }
    $resolvedPbf = $sliced.FullName
} else {
    throw 'Provide either -ChinaPbf or -Pbf'
}
$stagingPbf = Join-Path $stagingRoot 'osm\beijing-latest.osm.pbf'
New-Item -ItemType Directory -Path (Split-Path -Parent $stagingPbf) -Force | Out-Null
Copy-Item -LiteralPath $resolvedPbf -Destination $stagingPbf -Force
Write-Host "Pbf -> $stagingPbf"

# ---- 2. camera.json ----
$stagingCamera = Join-Path $stagingRoot 'cameras\camera.json'
New-Item -ItemType Directory -Path (Split-Path -Parent $stagingCamera) -Force | Out-Null
Copy-Item -LiteralPath $CameraJson -Destination $stagingCamera -Force
Write-Host "Camera -> $stagingCamera"

# ---- 3. 提取六环线与通州面，生成并发布边界 ----
$sixthRingPbf = Join-Path $tmpDir 'sixth-ring-lines.osm.pbf'
$sixthRingLines = Join-Path $tmpDir 'sixth-ring-lines.geojson'
$tongzhouPbf = Join-Path $tmpDir 'tongzhou.osm.pbf'
$tongzhouGeojson = Join-Path $tmpDir 'tongzhou.geojson'
& $osmiumPath getid --overwrite -r --output $sixthRingPbf $stagingPbf r295982
if ($LASTEXITCODE -ne 0) { throw 'osmium getid r295982 failed' }
& $osmiumPath export --output $sixthRingLines $sixthRingPbf
if ($LASTEXITCODE -ne 0) { throw 'osmium export sixth-ring lines failed' }
& $osmiumPath getid --overwrite -r --output $tongzhouPbf $stagingPbf r2988902
if ($LASTEXITCODE -ne 0) { throw 'osmium getid r2988902 failed' }
& $osmiumPath export --output $tongzhouGeojson $tongzhouPbf
if ($LASTEXITCODE -ne 0) { throw 'osmium export tongzhou failed' }

& $PSScriptRoot\generate-controlled-area-boundary.ps1 `
    -SixthRingLines $sixthRingLines `
    -TongzhouGeojson $tongzhouGeojson `
    -Pbf $stagingPbf `
    -DataRoot $stagingRoot `
    -Output (Join-Path $tmpDir 'boundary-candidate.geojson') `
    -Report (Join-Path $tmpDir 'boundary-report.md') `
    -Jar $jarPath `
    -Java $javaExecutable `
    -Publish -Force
if ($LASTEXITCODE -ne 0) { throw 'boundary generation failed' }

# ---- 4. 图缓存冷构图 ----
$stagingCache = Join-Path $stagingRoot 'graph-cache\beijing-compliant-time-v2'
& $javaExecutable -jar $jarPath graph-build --pbf $stagingPbf --cache-out $stagingCache
if ($LASTEXITCODE -ne 0) { throw 'graph-build failed' }

# ---- 5. 一致性校验（含 jar 配置指纹检查）----
& $PSScriptRoot\verify-data-consistency.ps1 -DataRoot $stagingRoot -Jar $jarPath -Java $javaExecutable
if ($LASTEXITCODE -ne 0) { throw 'data consistency check failed' }

# ---- 6. 打包 ----
$dataZip = Join-Path $resolvedPkgDir 'data.zip'
Compress-Archive -Path (Join-Path $stagingRoot '*') -DestinationPath $dataZip -Force
$serverJar = Join-Path $resolvedPkgDir 'server.jar'
Copy-Item -LiteralPath $jarPath -Destination $serverJar -Force
$zipSizeMB = [math]::Round((Get-Item $dataZip).Length / 1MB, 1)
$jarSizeMB = [math]::Round((Get-Item $serverJar).Length / 1MB, 1)
Write-Host "DATA_ZIP=$dataZip ($zipSizeMB MB)"
Write-Host "SERVER_JAR=$serverJar ($jarSizeMB MB)"
Write-Host "Deployment package ready. Upload data.zip and server.jar to the server."
