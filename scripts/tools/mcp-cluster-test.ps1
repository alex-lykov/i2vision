[CmdletBinding()]
param(
    [string]$ProjectPath = "core/orchestrator",
    [string]$Cluster = "orchestrator",
    [switch]$SkipConfigInit,
    [switch]$AllowNonRootProjectPath,
    [switch]$PreserveClusterMetadataChange,
    [ValidateSet("legacy", "dual", "cache")][string]$SemanticArtifactMode = "cache",
    [string]$RepresentativeFilePath = "src/main/kotlin/com/alyk/ai/koog/core/orchestrator/AgentOrchestrator.kt"
)
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$gradleWrapper = Join-Path $repoRoot "gradlew.bat"
if (-not (Test-Path $gradleWrapper)) {
    throw "gradlew.bat not found at: $gradleWrapper"
}
if ($SkipConfigInit) {
    Write-Host "-SkipConfigInit is deprecated and ignored; structure/config initialization is owned by /project load."
}

function Get-RelativeProjectPath {
    param(
        [Parameter(Mandatory = $true)][string]$BasePath,
        [Parameter(Mandatory = $true)][string]$FullPath
    )

    $resolvedBase = [System.IO.Path]::GetFullPath((Resolve-Path -LiteralPath $BasePath).Path).TrimEnd([char[]]@('\','/'))
    $resolvedFull = [System.IO.Path]::GetFullPath((Resolve-Path -LiteralPath $FullPath).Path).TrimEnd([char[]]@('\','/'))
    $basePrefix = $resolvedBase + '\\'
    if ($resolvedFull.StartsWith($basePrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        return $resolvedFull.Substring($basePrefix.Length).Replace('\\', '/')
    }

    $baseUri = New-Object System.Uri(($resolvedBase + '\\'))
    $fullUri = New-Object System.Uri(($resolvedFull + '\\'))
    return [System.Uri]::UnescapeDataString($baseUri.MakeRelativeUri($fullUri).ToString()).Replace('\\', '/')
}

# ProjectPath identifies the module under test.
# MCP state must be rooted at the repository root's .vision-ai/ directory.
$_rp = Resolve-Path -LiteralPath $ProjectPath -ErrorAction SilentlyContinue
$normalizedProject = if ($_rp) { $_rp.Path } else { $ProjectPath }
$_rr = Resolve-Path -LiteralPath $repoRoot -ErrorAction SilentlyContinue
$normalizedRoot    = if ($_rr) { $_rr.Path } else { $repoRoot }
if (-not (Test-Path -LiteralPath $ProjectPath)) {
    throw "ProjectPath not found: $ProjectPath"
}
if (-not $AllowNonRootProjectPath -and ($normalizedProject -eq $normalizedRoot)) {
    Write-Warning "ProjectPath is the repo root. Pass a module path (e.g. core/orchestrator) and use -AllowNonRootProjectPath to suppress this check."
}
$moduleRelativePath = Get-RelativeProjectPath -BasePath $repoRoot -FullPath $ProjectPath
$moduleSourceAbsolute = [System.IO.Path]::GetFullPath((Join-Path (Resolve-Path -LiteralPath $ProjectPath).Path "src/main"))
$rootPrefix = [System.IO.Path]::GetFullPath((Resolve-Path -LiteralPath $repoRoot).Path).TrimEnd([char[]]@('\','/')) + '\'
if ($moduleSourceAbsolute.StartsWith($rootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    $moduleSourceSubPath = $moduleSourceAbsolute.Substring($rootPrefix.Length).Replace('\', '/').Trim('/').TrimEnd('/')
}
else {
    $moduleSourceSubPath = (Get-RelativeProjectPath -BasePath $repoRoot -FullPath $moduleSourceAbsolute).Replace('\', '/').Trim('/').TrimEnd('/')
}
$moduleRelativePathNormalized = if ($moduleSourceSubPath -like '*/src/main') {
    $moduleSourceSubPath.Substring(0, $moduleSourceSubPath.Length - '/src/main'.Length)
}
else {
    $moduleRelativePath.Replace('\', '/').Trim('/').TrimEnd('/')
}
$rootVisionAi = Join-Path $repoRoot ".vision-ai"
if (-not (Test-Path -LiteralPath $rootVisionAi)) {
    throw "Expected root .vision-ai directory was not found: $rootVisionAi"
}

$nestedVisionAi = Get-ChildItem -Path $repoRoot -Directory -Recurse -Force -Filter ".vision-ai" |
    Where-Object {
        $_.FullName -ne $rootVisionAi -and
        $_.FullName -notlike "*\build\*" -and
        $_.FullName -notlike "*\.gradle\*" -and
        $_.FullName -notlike "*\.git\*"
    }
if ($nestedVisionAi) {
    $nestedList = ($nestedVisionAi | Sort-Object FullName | ForEach-Object { $_.FullName }) -join [Environment]::NewLine
    throw "Root-only .vision-ai rule violated. Nested .vision-ai directories found:`n$nestedList"
}

function Resolve-RepresentativeCodeFile {
    param(
        [Parameter(Mandatory = $true)][string]$ProjectRoot,
        [Parameter(Mandatory = $true)][string]$ClusterName,
        [string]$ExplicitPath = ""
    )

    $resolvedRoot = [System.IO.Path]::GetFullPath((Resolve-Path -LiteralPath $ProjectRoot).Path)
    $rootPrefix = $resolvedRoot.TrimEnd('\\') + '\\'

    if (-not [string]::IsNullOrWhiteSpace($ExplicitPath)) {
        $candidate = if ([System.IO.Path]::IsPathRooted($ExplicitPath)) { $ExplicitPath } else { Join-Path $ProjectRoot $ExplicitPath }
        if (-not (Test-Path -LiteralPath $candidate)) {
            throw "Representative file not found: $ExplicitPath"
        }
        $resolvedCandidate = [System.IO.Path]::GetFullPath((Resolve-Path -LiteralPath $candidate).Path)
        if ($resolvedCandidate.StartsWith($rootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
            return $resolvedCandidate.Substring($rootPrefix.Length).Replace('\\', '/')
        }
        return Get-RelativeProjectPath -BasePath $ProjectRoot -FullPath $candidate
    }

    $srcMain = Join-Path $ProjectRoot "src/main"
    if (-not (Test-Path -LiteralPath $srcMain)) {
        return $null
    }
    $resolvedSrcMain = [System.IO.Path]::GetFullPath((Resolve-Path -LiteralPath $srcMain).Path)
    $srcMainPrefix = $resolvedSrcMain.TrimEnd('\\') + '\\'

    $candidates = Get-ChildItem -Path $srcMain -Recurse -File |
        Where-Object { $_.Extension -in @('.kt', '.java') } |
        Sort-Object FullName

    if (-not $candidates) {
        return $null
    }

    $preferred = $candidates |
        Where-Object {
            $_.BaseName -like "*$ClusterName*" -or
            $_.DirectoryName -like "*$ClusterName*"
        } |
        Select-Object -First 1

    if (-not $preferred) {
        $preferred = $candidates | Select-Object -First 1
    }

    $resolvedPreferred = [System.IO.Path]::GetFullPath($preferred.FullName)
    if ($resolvedPreferred.StartsWith($srcMainPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        return "src/main/" + $resolvedPreferred.Substring($srcMainPrefix.Length).Replace('\\', '/')
    }
    if ($resolvedPreferred.StartsWith($rootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        return $resolvedPreferred.Substring($rootPrefix.Length).Replace('\\', '/')
    }

    return Get-RelativeProjectPath -BasePath $ProjectRoot -FullPath $preferred.FullName
}

function Get-ClusterFlowRefs {
    param(
        [Parameter(Mandatory = $true)][string]$ClusterFilePath
    )

    $refs = @()
    $inFlowBlock = $false
    foreach ($line in Get-Content -LiteralPath $ClusterFilePath) {
        if ($line -match '^flow:\s*$') {
            $inFlowBlock = $true
            continue
        }

        if (-not $inFlowBlock) {
            continue
        }

        if ($line -match '^[A-Za-z][A-Za-z0-9_]*:\s*$') {
            break
        }

        if ($line -match '^\s*-\s*(.+?)\s*$') {
            $refs += $Matches[1].Trim('"', "'")
        }
    }

    return $refs
}

function Get-ExpectedSemanticRoot {
    param(
        [Parameter(Mandatory = $true)][string]$ModuleRelativePath,
        [Parameter(Mandatory = $true)][string]$Mode
    )

    if ($Mode -eq "legacy") {
        if ($ModuleRelativePath -eq ".") { return "src" }
        return "$ModuleRelativePath/src"
    }

    if ($ModuleRelativePath -eq ".") { return ".semantic-cache/_root" }
    return ".semantic-cache/$ModuleRelativePath"
}

function Assert-FeatureArtifactHasScenarios {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string]$ArtifactRef
    )

    $content = Get-Content -LiteralPath $FilePath -Raw
    if ($content -notmatch '(?m)^\s*Scenario:') {
        throw "Generated Flow .feature artifact has no Scenario entries: $ArtifactRef"
    }
}

function Assert-SeqDiagArtifactHasInteractions {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string]$ArtifactRef
    )

    $content = Get-Content -LiteralPath $FilePath -Raw
    # Require at least one message edge to avoid title/participant-only diagrams.
    if ($content -notmatch '->') {
        throw "Generated Flow .sd artifact has no interactions: $ArtifactRef"
    }
}

function Assert-ContractsArtifactHasInteractions {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string]$ArtifactRef
    )

    $content = Get-Content -LiteralPath $FilePath -Raw
    if ($content -match '(?m)^\s*interactions:\s*\[\s*\]\s*$') {
        throw "Generated Flow contracts artifact has empty interactions list: $ArtifactRef"
    }

    $hasBlockInteractions = $content -match '(?ms)^\s*interactions:\s*\r?\n\s*-\s+'
    $hasInlineInteractions = $content -match '(?m)^\s*interactions:\s*\[\s*.+\s*\]\s*$'
    if (-not ($hasBlockInteractions -or $hasInlineInteractions)) {
        throw "Generated Flow contracts artifact does not contain interactions entries: $ArtifactRef"
    }
}

$representativeSymbol = if (-not [string]::IsNullOrWhiteSpace($RepresentativeFilePath)) {
    [System.IO.Path]::GetFileNameWithoutExtension($RepresentativeFilePath)
}
else {
    $Cluster
}

if ($representativeSymbol) {
    Write-Host "Representative symbol: $representativeSymbol"
}

$commands = @(
    "/project load $repoRoot"
    "/mcp status"
    "/mcp tools"
    "/mcp exec ai_scan_files sub_path=$moduleSourceSubPath"
)

if ($representativeSymbol) {
    $commands += "/mcp exec ai_find_symbol symbol=$representativeSymbol sub_path=$moduleSourceSubPath"
}

$commands += @(
    "/mcp exec ai_discover cluster=$Cluster sub_path=$moduleSourceSubPath"
    "/mcp clusters"
)

$commands += @(
    "/mcp cluster status $Cluster"
    "/mcp cluster context $Cluster"
    "/mcp sync"
    "/mcp validate"
    "/mcp cluster status $Cluster"
    "/exit"
)
$inputPayload = ($commands -join [Environment]::NewLine) + [Environment]::NewLine
$previousRolloutMode = $env:KOOG_MCP_ROLLOUT_MODE
$previousSemanticMode = $env:KOOG_SEMANTIC_ARTIFACT_MODE
$clusterFile = Join-Path $repoRoot ".vision-ai/clusters/$Cluster.yaml"
$clusterFileExistedBefore = Test-Path -LiteralPath $clusterFile
$clusterFileBackup = $null
if ($clusterFileExistedBefore) {
    $clusterFileBackup = Join-Path ([System.IO.Path]::GetTempPath()) ("mcp-cluster-test-{0}.yaml" -f [System.Guid]::NewGuid().ToString("N"))
    Copy-Item -LiteralPath $clusterFile -Destination $clusterFileBackup -Force
}
$env:KOOG_MCP_ROLLOUT_MODE = "hybrid"
$env:KOOG_SEMANTIC_ARTIFACT_MODE = $SemanticArtifactMode
$moduleLayerRoot = Get-ExpectedSemanticRoot -ModuleRelativePath $moduleRelativePathNormalized -Mode $SemanticArtifactMode
Write-Host "Running MCP cluster test for '$Cluster' using repo root '$repoRoot' and module '$ProjectPath'..."
Write-Host "Module source scope: $moduleSourceSubPath"
Write-Host "Semantic artifact mode: $SemanticArtifactMode"
Write-Host "This script validates only the repo-root .vision-ai directory; nested module .vision-ai directories are treated as failures."
try {
    $inputPayload | & $gradleWrapper ":launcher:run" "--args=--cli" "--console=plain"
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle launcher run failed with exit code $LASTEXITCODE"
    }

    if (-not (Test-Path -LiteralPath $clusterFile)) {
        throw "Expected cluster file was not created: $clusterFile"
    }

    $flowRefs = @(Get-ClusterFlowRefs -ClusterFilePath $clusterFile)
    if ($flowRefs.Count -eq 0) {
        throw "Cluster '$Cluster' does not contain any flow artifact references."
    }

    $parallelFlowRefs = @($flowRefs | Where-Object { $_ -like "$moduleLayerRoot/flow/*" })
    if ($parallelFlowRefs.Count -eq 0) {
        throw "Cluster '$Cluster' did not reference any Flow artifacts under expected root '$moduleLayerRoot/flow/'."
    }

    $sdRefs = @($parallelFlowRefs | Where-Object { $_ -like '*.sd' })
    $featureRefs = @($parallelFlowRefs | Where-Object { $_ -like '*.feature' })
    $contractsRefs = @($parallelFlowRefs | Where-Object { $_ -like '*/contracts.yaml' -or $_ -eq 'contracts.yaml' })

    if ($sdRefs.Count -eq 0) {
        throw "No .sd artifact was generated for cluster '$Cluster'."
    }
    if ($featureRefs.Count -eq 0) {
        throw "No .feature artifact was generated for cluster '$Cluster'."
    }
    if ($contractsRefs.Count -eq 0) {
        throw "No contracts.yaml artifact was generated for cluster '$Cluster'."
    }

    foreach ($ref in $parallelFlowRefs) {
        $artifactPath = Join-Path $repoRoot $ref
        if (-not (Test-Path -LiteralPath $artifactPath)) {
            throw "Cluster '$Cluster' references a missing Flow artifact: $ref"
        }
        $item = Get-Item -LiteralPath $artifactPath
        if ($item.Length -le 0) {
            throw "Generated Flow artifact is empty: $ref"
        }

        if ($ref -like '*.feature') {
            Assert-FeatureArtifactHasScenarios -FilePath $artifactPath -ArtifactRef $ref
        }
        elseif ($ref -like '*.sd') {
            Assert-SeqDiagArtifactHasInteractions -FilePath $artifactPath -ArtifactRef $ref
        }
        elseif ($ref -like '*/contracts.yaml' -or $ref -eq 'contracts.yaml') {
            Assert-ContractsArtifactHasInteractions -FilePath $artifactPath -ArtifactRef $ref
        }
    }

    Write-Host "PASS: Cluster '$Cluster' was discovered and synchronized using MCP discovery/sync tools."
    Write-Host "PASS: Repo-root .vision-ai/clusters/$Cluster.yaml references Flow artifacts under '$moduleLayerRoot/flow/'."
    Write-Host "PASS: Found Flow artifacts -> seqdiag=$($sdRefs.Count), feature=$($featureRefs.Count), contracts=$($contractsRefs.Count)."
}
finally {
    if ($null -eq $previousRolloutMode) {
        Remove-Item Env:KOOG_MCP_ROLLOUT_MODE -ErrorAction SilentlyContinue
    }
    else {
        $env:KOOG_MCP_ROLLOUT_MODE = $previousRolloutMode
    }

    if ($null -eq $previousSemanticMode) {
        Remove-Item Env:KOOG_SEMANTIC_ARTIFACT_MODE -ErrorAction SilentlyContinue
    }
    else {
        $env:KOOG_SEMANTIC_ARTIFACT_MODE = $previousSemanticMode
    }

    if (-not $PreserveClusterMetadataChange) {
        $clusterFileRelative = ".vision-ai/clusters/$Cluster.yaml"
        $isTracked = $false
        try {
            $null = & git -C $repoRoot ls-files --error-unmatch -- $clusterFileRelative 2>&1
            $isTracked = ($LASTEXITCODE -eq 0)
        } catch { $isTracked = $false }

        if ($isTracked) {
            & git -C $repoRoot restore -- $clusterFileRelative 2>&1 | Out-Null
        }
        elseif ($clusterFileExistedBefore -and $clusterFileBackup -and (Test-Path -LiteralPath $clusterFileBackup)) {
            Copy-Item -LiteralPath $clusterFileBackup -Destination $clusterFile -Force
        }
        elseif ((-not $clusterFileExistedBefore) -and (Test-Path -LiteralPath $clusterFile)) {
            Remove-Item -LiteralPath $clusterFile -Force
        }
    }

    if ($clusterFileBackup -and (Test-Path -LiteralPath $clusterFileBackup)) {
        Remove-Item -LiteralPath $clusterFileBackup -Force -ErrorAction SilentlyContinue
    }
}
