<#
Minimal Module Discovery Test (clean)

Runs `validate-links` (project_wide) and, if zero links are found, triggers `discover`.
#>

param(
    [string]$Cluster = 'orchestrator',
    [string]$ProjectPath = '.',
    [switch]$PurgeBeforeTest
)

$ErrorActionPreference = 'Stop'

try {
    # Resolve repository root
    $scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
    if ($ProjectPath -and $ProjectPath -ne '.') { $repoRoot = (Resolve-Path $ProjectPath).Path } else { $repoRoot = (Resolve-Path (Join-Path $scriptDir '..\..')).Path }
    Write-Host "Repository root: $repoRoot" -ForegroundColor Gray

    $gradlePath = Join-Path $repoRoot 'gradlew.bat'
    if (-not (Test-Path $gradlePath)) { Write-Error "gradlew.bat not found in repo root: $repoRoot"; exit 2 }

    $semanticCacheRoot = Join-Path $repoRoot '.semantic-cache'

    # Robust Gradle runner using ProcessStartInfo to avoid quoting pitfalls and capture output/exit code
    function Run-Gradle($cmdArgs, [int]$timeoutSeconds = 300) {
        Write-Host "Running: $gradlePath $cmdArgs" -ForegroundColor DarkGray

        $psi = New-Object System.Diagnostics.ProcessStartInfo
        # On Windows run the wrapper through cmd.exe so batch files execute correctly when UseShellExecute = $false
        $psi.FileName = 'cmd.exe'
        # Ensure the wrapper runs from the repository root so it can locate gradle files
        $psi.WorkingDirectory = $repoRoot
        # Wrap gradlew path in quotes and pass through args
        $escapedGradle = "`"$gradlePath`""
        $psi.Arguments = "/c $escapedGradle $cmdArgs --no-daemon --console=plain"
        $psi.RedirectStandardOutput = $true
        $psi.RedirectStandardError = $true
        $psi.UseShellExecute = $false
        $psi.CreateNoWindow = $true

        $proc = New-Object System.Diagnostics.Process
        $proc.StartInfo = $psi

        # Prepare collectors for output lines
        $outLines = New-Object System.Collections.Generic.List[string]
        $errLines = New-Object System.Collections.Generic.List[string]

        # Handlers to capture output as it arrives (compatible with .NET used by PowerShell)
        $outputHandler = [System.Diagnostics.DataReceivedEventHandler]{
            param($sender, $e)
            if ($e.Data -ne $null) { $outLines.Add($e.Data) }
        }
        $errorHandler = [System.Diagnostics.DataReceivedEventHandler]{
            param($sender, $e)
            if ($e.Data -ne $null) { $errLines.Add($e.Data) }
        }

        $proc.add_OutputDataReceived($outputHandler)
        $proc.add_ErrorDataReceived($errorHandler)

        $started = $proc.Start()
        if (-not $started) { throw "Failed to start gradle process" }

        # Begin asynchronous read of redirected streams
        $proc.BeginOutputReadLine()
        $proc.BeginErrorReadLine()

        if (-not $proc.WaitForExit($timeoutSeconds * 1000)) {
            try { $proc.Kill() } catch { }
            throw "Gradle timed out after ${timeoutSeconds}s"
        }

        # Allow final event handlers to flush
        Start-Sleep -Milliseconds 50

        $lines = @()
        $lines += $outLines
        $lines += $errLines

        return @{ exitCode = $proc.ExitCode; lines = $lines }
    }

    # Purge cache if requested
    if ($PurgeBeforeTest) {
        $clusterCache = Join-Path $semanticCacheRoot $Cluster
        if (Test-Path $clusterCache) { Write-Host "Purging cache for cluster: $Cluster" -ForegroundColor Yellow; Remove-Item -Recurse -Force $clusterCache }
    }

    Write-Host "Starting module discovery for cluster: $Cluster" -ForegroundColor Cyan

    # Determine links mode from strategy if available
    $strategyPath = Join-Path $repoRoot ".vision-ai\config\strategies\vlsfc-module-discovery-strategy.yaml"
    $linksMode = 'project_wide'

    if (Test-Path $strategyPath) {
        Write-Host "Parsing strategy: $strategyPath" -ForegroundColor Gray

        # Prepare logs dir for detailed outputs
        $logsRoot = Join-Path $semanticCacheRoot 'logs'
        if (-not (Test-Path $logsRoot)) { New-Item -ItemType Directory -Path $logsRoot | Out-Null }
        $ts = (Get-Date).ToString('yyyyMMdd-HHmmss')
        $strategyLog = Join-Path $logsRoot "strategy-parser-$ts.log"

        # Build Gradle invocation for the new JavaExec task (avoid nested --args quoting)
        $gradleArgs = ":launcher:strategyParser -PstrategyFile=`"$strategyPath`""
        Write-Host "Gradle invocation args: [$gradleArgs]" -ForegroundColor DarkGray

        $res = Run-Gradle $gradleArgs 60

        # Persist detailed gradle output to log
        try {
            $res.lines | Out-File -FilePath $strategyLog -Encoding utf8
            Write-Host "Detailed StrategyParser log: $strategyLog" -ForegroundColor DarkGray
        } catch {
            Write-Host "Failed to write strategy log: $($_.Exception.Message)" -ForegroundColor Yellow
        }

        Write-Host "StrategyParser run returned exit=$($res.exitCode) lines=$($res.lines.Count)" -ForegroundColor DarkGray

        # Try to find JSON output (first JSON-looking line)
        $jsonLine = $res.lines | Where-Object { $_ -match '^[\s\[\{]' } | Select-Object -First 1
        if ($jsonLine) {
            try {
                $parsed = $jsonLine | ConvertFrom-Json -ErrorAction Stop
            } catch {
                $parsed = @()
            }
        } else {
            $parsed = @()
        }

        # Print concise monitoring summary of strategy phases to console
        if ($parsed -and $parsed.Count -gt 0) {
            Write-Host "Strategy phases summary (monitoring):" -ForegroundColor Cyan
            $i = 0
            foreach ($entry in $parsed) {
                $i++
                $ename = $entry.name
                $emode = $entry.mode
                $ephase = $entry.phase
                $eparams = ($entry.params | ConvertTo-Json -Compress)
                Write-Host "[$i] name=$ename phase=$ephase mode=$emode params=$eparams" -ForegroundColor Gray
            }
            Write-Host "Total strategy entries: $($parsed.Count) | parsed at: $(Get-Date -Format o)" -ForegroundColor Green
            # Prefer explicit mcp_validate_links entry, otherwise pick first validate/link-like
            $found = $null
            foreach ($p in $parsed) { if ($p.name -eq 'mcp_validate_links') { $found = $p; break } }
            if (-not $found) { foreach ($p in $parsed) { if ($p.name -match 'validate|link') { $found = $p; break } } }
            if ($found) {
                if ($found.mode) { $linksMode = $found.mode } elseif ($found.params -and $found.params.mode) { $linksMode = $found.params.mode }
                Write-Host "Selected links mode from strategy: $linksMode" -ForegroundColor Cyan
            }
        } else {
            Write-Host "No actionable strategy entries found; using default links mode: $linksMode" -ForegroundColor Yellow
        }
    } else {
        Write-Host "Strategy file not found: $strategyPath; using default links mode: $linksMode" -ForegroundColor Yellow
    }

    # Prepare validate log
    $ts = (Get-Date).ToString('yyyyMMdd-HHmmss')
    $validateLog = Join-Path $logsRoot "validate-links-$ts.log"

    # Run validate-links with the chosen mode
    $validateArgs = ":configurable-agent:run --args=`"validate-links --module $Cluster --project `"$repoRoot`" --mode $linksMode`""
    Write-Host "Invoking validate-links (mode=$linksMode)" -ForegroundColor Gray
    $validateRes = Run-Gradle $validateArgs 300

    # Persist validate output to log
    try { $validateRes.lines | Out-File -FilePath $validateLog -Encoding utf8; Write-Host "Detailed validate-links log: $validateLog" -ForegroundColor DarkGray } catch { Write-Host "Failed to write validate log: $($_.Exception.Message)" -ForegroundColor Yellow }

    # Print the raw output for diagnostics (detailed output requirement)
    Write-Host "--- validate-links raw output ---" -ForegroundColor DarkGray
    $validateRes.lines | ForEach-Object { Write-Host $_ }
    Write-Host "--- end validate-links raw output ---" -ForegroundColor DarkGray

    # Try to extract machine-readable JSON from the tool output
    $jsonLine = $validateRes.lines | Where-Object { $_ -match '^[\t ]*\{' -or $_ -match '^[\t ]*\[' } | Select-Object -First 1
    if ($jsonLine) { try { $val = $jsonLine | ConvertFrom-Json -ErrorAction Stop } catch { $val = @{ raw = ($validateRes.lines -join "`n") } } } else { $val = @{ raw = ($validateRes.lines -join "`n") } }

    if ($val.projectLinksCreated -and $val.projectLinksCreated -gt 0) {
        Write-Host "Links present: $($val.projectLinksCreated)" -ForegroundColor Green
    } else {
        Write-Host "No links found or zero links created. Triggering discovery..." -ForegroundColor Yellow
        $discoverArgs = ":configurable-agent:run --args=`"discover --module $Cluster --project `"$repoRoot`" --mode full`""
        Write-Host "Invoking discover (full)" -ForegroundColor Gray
        $discoverRes = Run-Gradle $discoverArgs 1200

        # Persist discover output to log
        $discoverLog = Join-Path $logsRoot "discover-$ts.log"
        try { $discoverRes.lines | Out-File -FilePath $discoverLog -Encoding utf8; Write-Host "Detailed discover log: $discoverLog" -ForegroundColor DarkGray } catch { Write-Host "Failed to write discover log: $($_.Exception.Message)" -ForegroundColor Yellow }

        Write-Host "--- discover raw output ---" -ForegroundColor DarkGray
        $discoverRes.lines | ForEach-Object { Write-Host $_ }
        Write-Host "--- end discover raw output ---" -ForegroundColor DarkGray

        # Re-run validate-links after discovery
        $validateRes = Run-Gradle $validateArgs 300
        $postValidateLog = Join-Path $logsRoot "validate-links-postdiscover-$ts.log"
        try { $validateRes.lines | Out-File -FilePath $postValidateLog -Encoding utf8; Write-Host "Detailed post-discover validate log: $postValidateLog" -ForegroundColor DarkGray } catch { Write-Host "Failed to write post-validate log: $($_.Exception.Message)" -ForegroundColor Yellow }

        Write-Host "--- validate-links raw output (post-discover) ---" -ForegroundColor DarkGray
        $validateRes.lines | ForEach-Object { Write-Host $_ }
        Write-Host "--- end validate-links raw output (post-discover) ---" -ForegroundColor DarkGray
    }

    # Show the head of the links registry if present
    $linksPath = Join-Path $semanticCacheRoot 'links.yaml'
    if (Test-Path $linksPath) {
        Write-Host "`nGenerated links registry: $linksPath" -ForegroundColor Green
        Get-Content $linksPath -TotalCount 120 | ForEach-Object { Write-Host $_ }
    } else {
        Write-Host "No links.yaml produced." -ForegroundColor Red
    }

    Write-Host "Module discovery completed." -ForegroundColor Cyan
    exit 0
} catch {
    Write-Error "Module discovery failed: $($_.Exception.Message)"
    exit 2
}
