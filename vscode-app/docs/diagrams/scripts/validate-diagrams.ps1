# Script to validate .sd diagram syntax
# Usage: .\validate-diagrams.ps1

$ErrorActionPreference = "Continue"

$diagramsDir = $PSScriptRoot

# Get all .sd files
$sdFiles = Get-ChildItem -Path $diagramsDir -Filter "*.sd" -File

if ($sdFiles.Count -eq 0) {
    Write-Host "No .sd files found in $diagramsDir"
    exit 0
}

Write-Host "Validating $($sdFiles.Count) diagram(s)..."
Write-Host ""

$validCount = 0
$warningCount = 0
$errorCount = 0

foreach ($sdFile in $sdFiles) {
    $fileName = $sdFile.Name
    $content = Get-Content $sdFile.FullName -Raw -Encoding UTF8
    $lines = $content -split "`r?`n"
    
    $hasErrors = $false
    $hasWarnings = $false
    $messages = @()
    
    # Check 1: File is not empty
    if ([string]::IsNullOrWhiteSpace($content)) {
        $messages += "  ERROR: File is empty"
        $hasErrors = $true
    }
    
    # Check 2: Has title
    if ($lines.Length -eq 0 -or $lines[0] -notmatch "^title\s+.+") {
        $messages += "  WARNING: Missing 'title' on first line"
        $hasWarnings = $true
    }
    
    # Check 3: Has at least one participant
    $participantCount = ([regex]::Matches($content, '(?m)^participant\s+')).Count
    if ($participantCount -eq 0) {
        $messages += "  ERROR: No participants defined"
        $hasErrors = $true
    }
    
    # Check 4: Balanced fragments (alt/opt/loop/par/end)
    $altCount = ([regex]::Matches($content, '(?m)^\s*alt\s+')).Count
    $optCount = ([regex]::Matches($content, '(?m)^\s*opt\s+')).Count
    $loopCount = ([regex]::Matches($content, '(?m)^\s*loop\s+')).Count
    $parCount = ([regex]::Matches($content, '(?m)^\s*par\s+')).Count
    $groupCount = ([regex]::Matches($content, '(?m)^\s*group\s+')).Count
    $endCount = ([regex]::Matches($content, '(?m)^\s*end\s*$')).Count
    
    $fragmentStartCount = $altCount + $optCount + $loopCount + $parCount + $groupCount
    if ($fragmentStartCount -ne $endCount) {
        $messages += "  ERROR: Unbalanced fragments ($fragmentStartCount start tags, $endCount end tags)"
        $hasErrors = $true
    }
    
    # Check 5: Message syntax (basic check)
    $messageCount = ([regex]::Matches($content, '->|-->|<-|<<--')).Count
    if ($messageCount -eq 0 -and $participantCount -gt 1) {
        $messages += "  WARNING: No messages between participants"
        $hasWarnings = $true
    }
    
    # Check 6: Activate without deactivate
    $activateCount = ([regex]::Matches($content, '(?m)^\s*activate\s+')).Count
    $deactivateCount = ([regex]::Matches($content, '(?m)^\s*deactivate\s+')).Count
    if ($activateCount -ne $deactivateCount) {
        $messages += "  WARNING: Unbalanced activate/deactivate ($activateCount activate, $deactivateCount deactivate)"
        $hasWarnings = $true
    }
    
    # Check 7: Note syntax
    $noteOverCount = ([regex]::Matches($content, '(?m)^\s*note\s+over\s+')).Count
    $noteLeftRightCount = ([regex]::Matches($content, '(?m)^\s*note\s+(left|right)\s+of\s+')).Count
    $noteCount = $noteOverCount + $noteLeftRightCount
    if ($noteCount -gt 0) {
        # Check for colon after participant reference
        $noteWithColon = ([regex]::Matches($content, '(?m)^\s*note\s+(over|left of|right of)\s+[^:]+:')).Count
        if ($noteWithColon -ne $noteCount) {
            $messages += "  WARNING: Some notes may be missing ':' after participant reference"
            $hasWarnings = $true
        }
    }
    
    # Check 8: URL encoding safety (check for problematic characters)
    if ($content -match '[\x00-\x08\x0B\x0C\x0E-\x1F]') {
        $messages += "  WARNING: Contains control characters that may cause URL encoding issues"
        $hasWarnings = $true
    }
    
    # Check 9: File size (warn if very large)
    $fileSizeKB = [math]::Round($sdFile.Length / 1KB, 2)
    if ($fileSizeKB -gt 10) {
        $messages += "  WARNING: Large file (${fileSizeKB}KB) - may exceed URL length limits"
        $hasWarnings = $true
    }
    
    # Output results
    if ($hasErrors) {
        Write-Host "[ERROR] $fileName"
        $errorCount++
    } elseif ($hasWarnings) {
        Write-Host "[WARNING] $fileName"
        $warningCount++
    } else {
        Write-Host "[OK] $fileName"
        $validCount++
    }
    
    foreach ($msg in $messages) {
        Write-Host $msg
    }
}

Write-Host ""
Write-Host "Validation complete!"
Write-Host "  Valid: $validCount"
Write-Host "  Warnings: $warningCount"
Write-Host "  Errors: $errorCount"
Write-Host ""

if ($errorCount -gt 0) {
    Write-Host "Please fix errors before converting diagrams."
    exit 1
} else {
    exit 0
}
