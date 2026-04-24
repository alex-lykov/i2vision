# Script to convert .sd diagram files to sequencediagram.org URLs
# Usage: .\convert-diagram-to-link.ps1 <diagram-file.sd>

param(
    [Parameter(Mandatory=$true)]
    [string]$DiagramPath
)

$ErrorActionPreference = "Stop"

# Check if the diagram file exists
if (-not (Test-Path $DiagramPath)) {
    Write-Error "Diagram file not found: $DiagramPath"
    exit 1
}

# Read the diagram content
$content = Get-Content $DiagramPath -Raw -Encoding UTF8

# URL encode the content
$encoded = [System.Uri]::EscapeDataString($content)

# Build the sequencediagram.org URL
$url = "https://sequencediagram.org/index.html?presentationMode=readOnly&shrinkToFit=true#initialData=$encoded"

# Determine the output file path
$diagramName = [System.IO.Path]::GetFileNameWithoutExtension($DiagramPath)
$linksDir = Join-Path (Split-Path $DiagramPath -Parent) "links"
$outputPath = Join-Path $linksDir "$diagramName.md"

# Ensure the links directory exists
if (-not (Test-Path $linksDir)) {
    New-Item -ItemType Directory -Path $linksDir -Force | Out-Null
}

# Write the URL to the output file
$url | Out-File -FilePath $outputPath -Encoding UTF8 -NoNewline

Write-Host "Created link file: $outputPath"
