param(
    [string]$ProjectPath = (Get-Location).Path,
    [switch]$Apply,
    [switch]$PurgeAll,
    [switch]$KeepSemanticCache,
    [switch]$SkipLegacyConfigMigration
)

$ErrorActionPreference = "Stop"

function Write-Step {
    param([string]$Message)
    Write-Host "[semantic-reset] $Message"
}

function Ensure-Directory {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        New-Item -ItemType Directory -Path $Path -Force | Out-Null
    }
}

function Migrate-LegacyAgentConfigs {
    param(
        [string]$RootPath,
        [bool]$DoApply
    )

    $legacyToConfigMap = @{
        ".vision-ai/config/code-agent.yaml" = ".vision-ai/.code/agent-config.yaml"
        ".vision-ai/config/flow-agent.yaml" = ".vision-ai/.flow/agent-config.yaml"
        ".vision-ai/config/logic-agent.yaml" = ".vision-ai/.logic/agent-config.yaml"
        ".vision-ai/config/structure-agent.yaml" = ".vision-ai/.structure/agent-config.yaml"
        ".vision-ai/config/vision-agent.yaml" = ".vision-ai/.vision/agent-config.yaml"
    }

    foreach ($entry in $legacyToConfigMap.GetEnumerator()) {
        $sourcePath = Join-Path $RootPath $entry.Key
        $destPath = Join-Path $RootPath $entry.Value
        if (-not (Test-Path -LiteralPath $sourcePath)) {
            continue
        }
        Write-Step "Migrating legacy config: $sourcePath -> $destPath"
        if ($DoApply) {
            Ensure-Directory -Path (Split-Path -Parent $destPath)
            Copy-Item -LiteralPath $sourcePath -Destination $destPath -Force
        }
    }
}

$root = [System.IO.Path]::GetFullPath($ProjectPath)
$visionRoot = Join-Path $root ".vision-ai"
$cacheRoot = Join-Path $root ".semantic-cache"

if (-not (Test-Path -LiteralPath $root)) {
    throw "Project path does not exist: $root"
}

Write-Step "Project root: $root"
Write-Step "Mode: $(if ($Apply) { "apply" } else { "preview" })"
Write-Step "Purge all .vision-ai content: $PurgeAll"
Write-Step "Keep .semantic-cache: $KeepSemanticCache"
Write-Step "Skip legacy config migration: $SkipLegacyConfigMigration"

$preserveNames = @("config", "clusters", "overrides", "cross-module", "README.md", ".gitignore")
$scaffoldDirs = @(
    ".vision-ai/config",
    ".vision-ai/clusters",
    ".vision-ai/overrides/flow",
    ".vision-ai/overrides/logic",
    ".vision-ai/overrides/structure",
    ".vision-ai/overrides/vision",
    ".vision-ai/cross-module"
)

if (-not (Test-Path -LiteralPath $visionRoot)) {
    Write-Step "Missing .vision-ai; scaffold will be created"
} else {
    $items = Get-ChildItem -LiteralPath $visionRoot -Force
    if ((-not $SkipLegacyConfigMigration) -and (-not $PurgeAll)) {
        Migrate-LegacyAgentConfigs -RootPath $root -DoApply:$Apply
    }
    if ($PurgeAll) {
        Write-Step "Removing full .vision-ai"
        if ($Apply) {
            Remove-Item -LiteralPath $visionRoot -Recurse -Force
        }
    } else {
        foreach ($item in $items) {
            if ($preserveNames -contains $item.Name) {
                continue
            }
            Write-Step "Removing stale .vision-ai item: $($item.FullName)"
            if ($Apply) {
                Remove-Item -LiteralPath $item.FullName -Recurse -Force
            }
        }
    }
}

foreach ($relativeDir in $scaffoldDirs) {
    $fullPath = Join-Path $root $relativeDir
    Write-Step "Ensuring directory: $fullPath"
    if ($Apply) {
        Ensure-Directory -Path $fullPath
    }
}

if (-not $KeepSemanticCache) {
    if (Test-Path -LiteralPath $cacheRoot) {
        Write-Step "Removing generated cache: $cacheRoot"
        if ($Apply) {
            Remove-Item -LiteralPath $cacheRoot -Recurse -Force
        }
    } else {
        Write-Step "No .semantic-cache directory found"
    }
} else {
    Write-Step "Keeping .semantic-cache as requested"
}

Write-Step "Done"
if (-not $Apply) {
    Write-Step "Preview only. Re-run with -Apply to execute changes."
}
