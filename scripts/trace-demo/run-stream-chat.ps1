[CmdletBinding()]
param(
    [string]$BaseUrl = $(if ($env:AGENT_BASE_URL) { $env:AGENT_BASE_URL } else { "http://localhost:8231/api" })
)

$ErrorActionPreference = "Stop"
$caseId = "TRACE_STREAM_CHAT_BASELINE_20260730"
$payload = @{
    message = "请用一句话说明这个图片 Agent 的核心能力。"
    chatId = "github-trace-stream-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())"
    mode = "chat"
} | ConvertTo-Json -Compress

Add-Type -AssemblyName System.Net.Http
$handler = New-Object System.Net.Http.HttpClientHandler
$client = New-Object System.Net.Http.HttpClient($handler)
$request = New-Object System.Net.Http.HttpRequestMessage(
    [System.Net.Http.HttpMethod]::Post, "$BaseUrl/chat/stream")
$request.Headers.Add("X-Demo-Case-Id", $caseId)
$request.Content = New-Object System.Net.Http.StringContent(
    $payload, [Text.Encoding]::UTF8, "application/json")
$watch = [Diagnostics.Stopwatch]::StartNew()
$response = $client.SendAsync(
    $request, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead
).GetAwaiter().GetResult()
if (-not $response.IsSuccessStatusCode) {
    throw "Streaming demo failed with HTTP $([int]$response.StatusCode)"
}

$reader = New-Object IO.StreamReader(
    $response.Content.ReadAsStreamAsync().GetAwaiter().GetResult(),
    [Text.Encoding]::UTF8)
$eventTypes = New-Object System.Collections.Generic.List[string]
$firstTokenMs = $null
$hasError = $false
$hasDone = $false
while (-not $reader.EndOfStream) {
    $line = $reader.ReadLine()
    if (-not $line.StartsWith("data:")) {
        continue
    }
    $event = $line.Substring(5) | ConvertFrom-Json
    $eventTypes.Add([string]$event.type)
    if ($event.type -eq "token" -and $null -eq $firstTokenMs) {
        $firstTokenMs = $watch.ElapsedMilliseconds
    }
    if ($event.type -eq "error") {
        $hasError = $true
    }
    if ($event.type -eq "done") {
        $hasDone = $true
    }
}
$watch.Stop()

$reader.Dispose()
$response.Dispose()
$request.Dispose()
$client.Dispose()
$handler.Dispose()

if ($hasError -or -not $hasDone) {
    throw "Streaming demo ended without a successful done event"
}

[ordered]@{
    httpStatus = 200
    demoCaseId = $caseId
    firstVisibleTokenMs = $firstTokenMs
    totalMs = $watch.ElapsedMilliseconds
    eventCount = $eventTypes.Count
    eventTypes = @($eventTypes | Select-Object -Unique)
    contentPrinted = $false
} | ConvertTo-Json
Write-Host "Jaeger: search service=aiagent, tag agent.demo.case_id=$caseId"
