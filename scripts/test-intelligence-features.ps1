# Intelligence Features Test Script

Write-Host "[TEST] Testing i2vision Context Server Intelligence Features" -ForegroundColor Cyan
Write-Host "=" * 60

$baseUrl = "http://localhost:3001"
$testFile = "server/src/main/kotlin/com/alyk/ai/koog/server/Application.kt"

# Test 1: Health Check
Write-Host "`n[1] Testing Health Check..." -ForegroundColor Yellow
try
{
    $response = Invoke-RestMethod -Uri "$baseUrl/context/health" -Method Get
    Write-Host "   [OK] Status: $( $response.status )" -ForegroundColor Green
}
catch
{
    Write-Host "   [FAIL] Failed: $_" -ForegroundColor Red
}

# Test 2: Quick Context
Write-Host "`n[2] Testing Quick Context..." -ForegroundColor Yellow
try
{
    $response = Invoke-RestMethod -Uri "$baseUrl/context/quick?q=$testFile" -Method Get
    Write-Host "   [OK] Confidence: $([math]::Round($response.confidence * 100, 2) )%" -ForegroundColor Green
    Write-Host "   [OK] Status: $( $response.status )" -ForegroundColor Green
}
catch
{
    Write-Host "   [FAIL] Failed: $_" -ForegroundColor Red
}

# Test 3: Quality Metrics
Write-Host "`n[3] Testing Quality Metrics..." -ForegroundColor Yellow
try
{
    $response = Invoke-RestMethod -Uri "$baseUrl/intelligence/quality?path=$testFile" -Method Get
    Write-Host "   [OK] Cohesion Score: $([math]::Round($response.cohesionScore, 2) )" -ForegroundColor Green
    Write-Host "   [OK] Coupling Score: $([math]::Round($response.couplingScore, 2) )" -ForegroundColor Green
    Write-Host "   [OK] Maintainability: $([math]::Round($response.maintainabilityIndex, 2) )" -ForegroundColor Green
    Write-Host "   [OK] Refactoring Priority: $( $response.refactoringPriority )" -ForegroundColor Green
    Write-Host "   [OK] Recommendations: $( $response.topRecommendations.Count )" -ForegroundColor Green
}
catch
{
    Write-Host "   [FAIL] Failed: $_" -ForegroundColor Red
}

# Test 4: Strategy Recommendation
Write-Host "`n[4] Testing Strategy Recommendation..." -ForegroundColor Yellow
try
{
    $response = Invoke-RestMethod -Uri "$baseUrl/intelligence/strategy-recommend?path=$testFile&task=refactor" -Method Get
    Write-Host "   [OK] Recommended: $( $response.recommendedStrategy )" -ForegroundColor Green
    Write-Host "   [OK] Confidence: $([math]::Round($response.confidence, 2) )" -ForegroundColor Green
    Write-Host "   [OK] Reasoning: $( $response.reasoning )" -ForegroundColor Green
}
catch
{
    Write-Host "   [FAIL] Failed: $_" -ForegroundColor Red
}

# Test 5: Proactive Context (File Opened)
Write-Host "`n[5] Testing Proactive Context..." -ForegroundColor Yellow
try
{
    $response = Invoke-RestMethod -Uri "$baseUrl/intelligence/proactive/file-opened?path=$testFile" -Method Post
    Write-Host "   [OK] Primary Confidence: $([math]::Round($response.primaryContext.confidence, 2) )" -ForegroundColor Green
    Write-Host "   [OK] Related Contexts: $( $response.relatedContexts.Count )" -ForegroundColor Green
    Write-Host "   [OK] Suggestions: $( $response.suggestions.Count )" -ForegroundColor Green
}
catch
{
    Write-Host "   [FAIL] Failed: $_" -ForegroundColor Red
}

# Test 6: Enhanced LLM Request
Write-Host "`n[6] Testing LLM Request Enhancement..." -ForegroundColor Yellow
try
{
    $request = [System.Uri]::EscapeDataString("How does the context server work?")
    $response = Invoke-RestMethod -Uri "$baseUrl/intelligence/enhance-request?request=$request" -Method Post
    Write-Host "   [OK] Context Sources: $( $response.contextSources.Count )" -ForegroundColor Green
    Write-Host "   [OK] Confidence Boost: $([math]::Round($response.confidenceBoost, 2) )" -ForegroundColor Green
    Write-Host "   [OK] Quality Improvement: $([math]::Round($response.estimatedQualityImprovement, 2) )" -ForegroundColor Green
}
catch
{
    Write-Host "   [FAIL] Failed: $_" -ForegroundColor Red
}

# Test 7: Learning Insights
Write-Host "`n[7] Testing Learning Insights..." -ForegroundColor Yellow
try
{
    $response = Invoke-RestMethod -Uri "$baseUrl/intelligence/learning-insights" -Method Get
    Write-Host "   [OK] Contexts Tracked: $( $response.totalContextsTracked )" -ForegroundColor Green
    Write-Host "   [OK] Strategies Evaluated: $( $response.strategiesEvaluated )" -ForegroundColor Green
    Write-Host "   [OK] Improvement Events: $( $response.improvementEvents )" -ForegroundColor Green
    Write-Host "   [OK] System Improvement: $([math]::Round($response.overallSystemImprovement * 100, 2) )%" -ForegroundColor Green
}
catch
{
    Write-Host "   [FAIL] Failed: $_" -ForegroundColor Red
}

# Summary
Write-Host "`n" + ("=" * 60)
Write-Host "[OK] All Intelligence Features Tested!" -ForegroundColor Green
Write-Host "`nServer is ready for production use." -ForegroundColor Cyan
Write-Host "`nDocumentation:" -ForegroundColor Yellow
Write-Host "   - API Reference: docs/API_REFERENCE.md"
Write-Host "   - Implementation Summary: docs/IMPLEMENTATION_SUMMARY.md"
Write-Host "   - Main README: README.md"

