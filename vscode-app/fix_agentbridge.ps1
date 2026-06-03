$filePath = "D:\proj\AI\i2-vision\vscode-app\src\agent\AgentBridge.ts"
$content = Get-Content $filePath -Raw -Encoding UTF8

# Fix 1: Success messages
$oldSuccess = "content: resultText,"
$newSuccess = @"
content: `[SUCCESS`] Tool executed successfully.

Result:
`$resultText

[INSTRUCTION: You now have the information you requested. Summarize the findings for the user. Do NOT call more tools unless you need additional information.]
"@

$content = $content.Replace($oldSuccess, $newSuccess)

# Fix 2: Error messages
$oldError = "content: ``Error: ``\$error.message``,"
$newError = @"
content: `[ERROR`] Tool execution failed.

Error: `$error.message

[INSTRUCTION: Try a different approach or explain the issue to the user. Do NOT retry the same tool call.]
"@

$content = $content.Replace($oldError, $newError)

Set-Content $filePath $content -Encoding UTF8 -NoNewline
Write-Host "File updated successfully"
