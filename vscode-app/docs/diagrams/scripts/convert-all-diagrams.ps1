# Script to convert ALL .sd diagram files to sequencediagram.org URLs
# Usage: .\convert-all-diagrams.ps1

$ErrorActionPreference = "Stop"

$diagramsDir = $PSScriptRoot
$linksDir = Join-Path $diagramsDir "links"

# Ensure the links directory exists
if (-not (Test-Path $linksDir)) {
    New-Item -ItemType Directory -Path $linksDir -Force | Out-Null
}

# Get all .sd files
$sdFiles = Get-ChildItem -Path $diagramsDir -Filter "*.sd" -File

if ($sdFiles.Count -eq 0) {
    Write-Host "No .sd files found in $diagramsDir"
    exit 0
}

Write-Host "Converting $($sdFiles.Count) diagram(s)..."
Write-Host ""

$successCount = 0
$failCount = 0

foreach ($sdFile in $sdFiles) {
    try {
        $diagramName = $sdFile.BaseName
        
        # Read the diagram content
        $content = Get-Content $sdFile.FullName -Raw -Encoding UTF8
        
        # Extract the title from the first line
        $title = "Diagram"
        $lines = $content -split "`r?`n"
        if ($lines.Length -gt 0 -and $lines[0] -match "^title\s+(.+)$") {
            $title = $matches[1].Trim()
        }
        
        # URL encode the content
        $encoded = [System.Uri]::EscapeDataString($content)
        
        # Build the sequencediagram.org URL
        $url = "https://sequencediagram.org/index.html?presentationMode=readOnly&shrinkToFit=true#initialData=$encoded"
        
        # Build markdown link
        $markdownLink = "[$title]($url)"
        
        # Determine the output file path
        $outputPath = Join-Path $linksDir "$diagramName.md"
        
        # Write the markdown link to the output file
        $markdownLink | Out-File -FilePath $outputPath -Encoding UTF8 -NoNewline
        
        Write-Host "[OK] Converted: $diagramName.sd -> $diagramName.md"
        $successCount++
    }
    catch {
        Write-Host "[FAIL] Failed: $($sdFile.Name) - $($_.Exception.Message)"
        $failCount++
    }
}

Write-Host ""
Write-Host "Conversion complete!"
Write-Host "  Success: $successCount"
Write-Host "  Failed: $failCount"
Write-Host ""
Write-Host "Link files are in: $linksDir"
