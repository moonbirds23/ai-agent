[CmdletBinding()]
param(
    [string]$ChatId = "observability-smoke-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())",
    [ValidateRange(1, 60)]
    [int]$ExportWaitSeconds = 8
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $repoRoot ".env.local"

function Import-LocalEnvironment {
    param([Parameter(Mandatory)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }
    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith("#")) {
            continue
        }
        $separator = $trimmed.IndexOf("=")
        if ($separator -lt 1) {
            continue
        }
        $name = $trimmed.Substring(0, $separator).Trim()
        $value = $trimmed.Substring($separator + 1).Trim().Trim('"').Trim("'")
        [Environment]::SetEnvironmentVariable($name, $value, "Process")
    }
}

function Assert-True {
    param(
        [Parameter(Mandatory)][bool]$Condition,
        [Parameter(Mandatory)][string]$Message
    )
    if (-not $Condition) {
        throw $Message
    }
    Write-Host "[pass] $Message"
}

Import-LocalEnvironment -Path $envFile

$headers = @{}
if (-not [string]::IsNullOrWhiteSpace($env:APP_API_KEY)) {
    $headers["X-API-Key"] = $env:APP_API_KEY
}

$health = Invoke-RestMethod -Uri "http://localhost:8231/api/actuator/health"
Assert-True ($health.status -eq "UP") "Application health is UP"

$body = @{
    message = "可观测性冒烟验收：请只回复 OK"
    chatId = $ChatId
    mode = "chat"
} | ConvertTo-Json
$response = Invoke-RestMethod `
    -Method Post `
    -Uri "http://localhost:8231/api/chat" `
    -Headers $headers `
    -ContentType "application/json; charset=utf-8" `
    -TimeoutSec 60 `
    -Body $body
Assert-True ($response.code -eq 0) "Chat request completed"

Start-Sleep -Seconds $ExportWaitSeconds

$metrics = Invoke-WebRequest `
    -Uri "http://localhost:8231/api/actuator/prometheus" `
    -UseBasicParsing
Assert-True ($metrics.Content.Contains("agent_turn_seconds_count")) `
    "Micrometer exposes Agent metrics"

$targets = Invoke-RestMethod -Uri "http://localhost:9090/api/v1/targets"
$agentTarget = $targets.data.activeTargets |
    Where-Object { $_.labels.job -eq "ai-agent" } |
    Select-Object -First 1
Assert-True ($null -ne $agentTarget -and $agentTarget.health -eq "up") `
    "Prometheus scrapes ai-agent"

$promQuery = Invoke-RestMethod `
    -Uri "http://localhost:9090/api/v1/query?query=sum(agent_turn_seconds_count)"
Assert-True ($promQuery.status -eq "success" -and $promQuery.data.result.Count -gt 0) `
    "Prometheus can query Agent turns"

$grafanaHealth = Invoke-RestMethod -Uri "http://localhost:3000/api/health"
Assert-True ($grafanaHealth.database -eq "ok") "Grafana health is OK"
$dashboard = Invoke-RestMethod `
    -Uri "http://localhost:3000/api/dashboards/uid/agent-observability"
Assert-True ($dashboard.dashboard.uid -eq "agent-observability") `
    "Grafana dashboard is provisioned"

$jaegerServices = Invoke-RestMethod -Uri "http://localhost:16686/api/services"
Assert-True ($jaegerServices.data -contains "aiagent") `
    "Jaeger has the aiagent service"
$traces = Invoke-RestMethod `
    -Uri "http://localhost:16686/api/traces?service=aiagent&lookback=1h&limit=20"
Assert-True ($traces.data.Count -gt 0) "Jaeger has recent Agent traces"

Write-Host ""
Write-Host "Observability smoke test passed."
Write-Host "Chat ID:    $ChatId"
Write-Host "Grafana:    http://localhost:3000/d/agent-observability"
Write-Host "Jaeger:     http://localhost:16686/search?service=aiagent"
Write-Host "Prometheus: http://localhost:9090/targets"
