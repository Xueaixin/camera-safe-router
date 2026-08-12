[CmdletBinding()]
param(
    # 工作区根目录（默认 code 同级 workspace）。
    [string]$WorkspaceRoot,
    # 目标 osmium conda 环境目录（默认 workspace\tools\osmium-env）。
    [string]$OsmiumEnv,
    # micromamba 可执行文件（默认 workspace\tools\micromamba\Library\bin\micromamba.exe）。
    [string]$MicromambaPath,
    # osmium-tool 版本。
    [string]$Version = '1.19.1',
    # 目标环境已存在时强制重建（先删除再创建）。
    [switch]$Force
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
$envPath = [System.IO.Path]::GetFullPath((Resolve-DefaultPath $OsmiumEnv (Join-Path $workspaceRoot 'tools\osmium-env')))
$micromambaPath = [System.IO.Path]::GetFullPath((Resolve-DefaultPath $MicromambaPath (Join-Path $workspaceRoot 'tools\micromamba\Library\bin\micromamba.exe')))
$osmiumExe = Join-Path $envPath 'Library\bin\osmium.exe'

Write-Host "Osmium env   : $envPath"
Write-Host "Micromamba   : $micromambaPath"

if (Test-Path -LiteralPath $osmiumExe -PathType Leaf) {
    Write-Host "osmium already installed: $osmiumExe"
    & $osmiumExe --version
    if ($LASTEXITCODE -ne 0) { throw 'osmium --version check failed' }
    if (-not $Force) { Write-Host ("OSMIUM_OK=" + $osmiumExe); exit 0 }
}

if (-not (Test-Path -LiteralPath $micromambaPath -PathType Leaf)) {
    throw "micromamba not found: $micromambaPath"
}
if (Test-Path -LiteralPath $envPath) {
    if (-not $Force) {
        throw "target env exists but osmium is missing: $envPath ; rerun with -Force to rebuild"
    }
    $full = [System.IO.Path]::GetFullPath($envPath)
    $toolsRoot = [System.IO.Path]::GetFullPath((Join-Path $workspaceRoot 'tools')) + '\'
    if (-not $full.StartsWith($toolsRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "unsafe env path: $full"
    }
    Remove-Item -LiteralPath $full -Recurse -Force
}

& $micromambaPath create -p $envPath -c conda-forge -y "osmium-tool=$Version"
if ($LASTEXITCODE -ne 0) { throw 'micromamba create failed' }
if (-not (Test-Path -LiteralPath $osmiumExe -PathType Leaf)) {
    throw "osmium not created at $osmiumExe"
}
& $osmiumExe --version
if ($LASTEXITCODE -ne 0) { throw 'osmium --version check failed' }
Write-Host "OSMIUM_OK=$osmiumExe"
