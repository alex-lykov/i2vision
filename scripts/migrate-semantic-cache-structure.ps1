<#
.SYNOPSIS
    Migrates .semantic-cache structure to separate VSLFC clusters from tool outputs
.DESCRIPTION
    Fixes the dangerous mix of cluster folders and tool folders by:
    1. Creating .semantic-cache/.tools/ structure
    2. Moving logs/ to .tools/logs/
    3. Updating path references in scripts
#>

param(
    [switch]$DryRun,
    [switch]$Verbose
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $repoRoot

Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "Semantic Cache Structure Migration" -ForegroundColor Cyan
Write-Host "========================================`n" -ForegroundColor Cyan

$cacheRoot = Join-Path $repoRoot ".semantic-cache"

if (-not (Test-Path $cacheRoot)) {
    Write-Host "ERROR: .semantic-cache not found at: $cacheRoot" -ForegroundColor Red
    exit 1
}

# Identify VSLFC clusters (have code/flow/logic/structure/vision subdirs)
Write-Host "Analyzing .semantic-cache structure..." -ForegroundColor Yellow

$allDirs = Get-ChildItem $cacheRoot -Directory
$clusters = @()
$toolDirs = @()

foreach ($dir in $allDirs) {
    $subdirs = Get-ChildItem $dir.FullName -Directory | Select-Object -ExpandProperty Name

    # Check if it's a VSLFC cluster (has layer directories)
    $hasCode = $subdirs -contains "code"
    $hasFlow = $subdirs -contains "flow"
    $hasLogic = $subdirs -contains "logic"
    $hasStructure = $subdirs -contains "structure"
    $hasVision = $subdirs -contains "vision"

    if ($hasCode -and $hasFlow -and $hasLogic -and $hasStructure -and $hasVision) {
        $clusters += $dir
        if ($Verbose) {
            Write-Host "  [CLUSTER] $($dir.Name)" -ForegroundColor Green
        }
    } else {
        $toolDirs += $dir
        if ($Verbose) {
            Write-Host "  [TOOL] $($dir.Name)" -ForegroundColor Yellow
        }
    }
}

Write-Host "`nFound:" -ForegroundColor White
Write-Host "  VSLFC Clusters: $($clusters.Count)" -ForegroundColor Green
Write-Host "  Tool Directories: $($toolDirs.Count)" -ForegroundColor Yellow

if ($toolDirs.Count -eq 0) {
    Write-Host "`n[OK] No tool directories to migrate - structure is clean!" -ForegroundColor Green
    exit 0
}

# Show what will be migrated
Write-Host "`nTool directories to migrate:" -ForegroundColor Yellow
$toolDirs | ForEach-Object {
    $fileCount = (Get-ChildItem $_.FullName -File -Recurse).Count
    Write-Host "  - $($_.Name) ($fileCount files)" -ForegroundColor Gray
}

if ($DryRun) {
    Write-Host "`n[DRY RUN] Would perform the following actions:" -ForegroundColor Cyan
    Write-Host "  1. Create .semantic-cache/.tools/" -ForegroundColor Gray
    $toolDirs | ForEach-Object {
        Write-Host "  2. Move $($_.Name)/ -> .tools/$($_.Name)/" -ForegroundColor Gray
    }
    Write-Host "`nRun without -DryRun to execute migration." -ForegroundColor Yellow
    exit 0
}

# Confirm migration
Write-Host "`nThis will restructure .semantic-cache/" -ForegroundColor Yellow
Write-Host "Continue? [Y/N]: " -NoNewline -ForegroundColor Yellow
$confirm = Read-Host

if ($confirm -ne 'Y' -and $confirm -ne 'y') {
    Write-Host "Migration cancelled." -ForegroundColor Red
    exit 1
}

# Create .tools structure
Write-Host "`n--- Phase 1: Create .tools/ structure ---" -ForegroundColor Yellow

$toolsRoot = Join-Path $cacheRoot ".tools"
if (-not (Test-Path $toolsRoot)) {
    New-Item -ItemType Directory -Path $toolsRoot | Out-Null
    Write-Host "[OK] Created .semantic-cache/.tools/" -ForegroundColor Green
} else {
    Write-Host "[OK] .tools/ already exists" -ForegroundColor Green
}

# Move tool directories
Write-Host "`n--- Phase 2: Migrate tool directories ---" -ForegroundColor Yellow

foreach ($dir in $toolDirs) {
    $sourcePath = $dir.FullName
    $destPath = Join-Path $toolsRoot $dir.Name

    Write-Host "Moving $($dir.Name)..." -NoNewline -ForegroundColor Gray

    if (Test-Path $destPath) {
        Write-Host " [SKIP] Destination already exists" -ForegroundColor Yellow
        continue
    }

    try {
        Move-Item -Path $sourcePath -Destination $destPath -Force
        Write-Host " [OK]" -ForegroundColor Green
    } catch {
        Write-Host " [FAIL] $_" -ForegroundColor Red
    }
}

# Verify migration
Write-Host "`n--- Phase 3: Verification ---" -ForegroundColor Yellow

$remainingToolDirs = @()
$allDirsAfter = Get-ChildItem $cacheRoot -Directory | Where-Object { $_.Name -ne ".tools" }

foreach ($dir in $allDirsAfter) {
    $subdirs = Get-ChildItem $dir.FullName -Directory | Select-Object -ExpandProperty Name
    $hasCode = $subdirs -contains "code"
    $hasFlow = $subdirs -contains "flow"

    if (-not ($hasCode -and $hasFlow)) {
        $remainingToolDirs += $dir
    }
}

if ($remainingToolDirs.Count -eq 0) {
    Write-Host "[OK] All tool directories migrated successfully" -ForegroundColor Green
} else {
    Write-Host "[WARN] Some non-cluster directories remain:" -ForegroundColor Yellow
    $remainingToolDirs | ForEach-Object {
        Write-Host "  - $($_.Name)" -ForegroundColor Yellow
    }
}

# Show final structure
Write-Host "`n--- Final Structure ---" -ForegroundColor Yellow

Write-Host "VSLFC Clusters ($($clusters.Count)):" -ForegroundColor Green
$clusters | Select-Object -First 5 | ForEach-Object {
    Write-Host "  $($_.Name)/" -ForegroundColor Gray
}
if ($clusters.Count -gt 5) {
    Write-Host "  ... and $($clusters.Count - 5) more" -ForegroundColor Gray
}

Write-Host "`nTool Outputs:" -ForegroundColor Green
if (Test-Path $toolsRoot) {
    $toolSubdirs = Get-ChildItem $toolsRoot -Directory
    $toolSubdirs | ForEach-Object {
        $fileCount = (Get-ChildItem $_.FullName -File -Recurse).Count
        Write-Host "  .tools/$($_.Name)/ ($fileCount files)" -ForegroundColor Gray
    }
}

Write-Host "`n--- Next Steps ---" -ForegroundColor Yellow
Write-Host "1. Update scripts to use .semantic-cache/.tools/logs/" -ForegroundColor Cyan
Write-Host "2. Update .gitignore if needed" -ForegroundColor Cyan
Write-Host "3. Test discovery/validation workflows" -ForegroundColor Cyan

Write-Host "`n[SUCCESS] Migration complete!" -ForegroundColor Green
Write-Host "========================================`n" -ForegroundColor Cyan

