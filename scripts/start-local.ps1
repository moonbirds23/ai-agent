[CmdletBinding()]
param(
    [switch]$Observability,
    [ValidateRange(15, 300)]
    [int]$StartupTimeoutSeconds = 90
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $repoRoot ".env.local"
$runtimeDir = Join-Path $repoRoot "var"
$stateFile = Join-Path $runtimeDir "local-services.json"
$mcpPom = Join-Path $repoRoot "mcp-servers\image-retrieval-server\pom.xml"
$localProfileConfig = Join-Path $repoRoot "src\main\resources\application-local.yml"
$localJaegerDir = Join-Path $runtimeDir "jaeger"
$localPrometheusDir = Join-Path $runtimeDir "prometheus"
$localGrafanaDir = Join-Path $runtimeDir "grafana"
$prometheusConfig = Join-Path $repoRoot "observability\prometheus\prometheus.yml"
$grafanaProvisioningDir = Join-Path $repoRoot "observability\grafana\provisioning"
$grafanaDashboardsDir = Join-Path $repoRoot "observability\grafana\dashboards"

function Import-LocalEnvironment {
    param([Parameter(Mandatory)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Missing local configuration: $Path"
    }

    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith("#")) {
            continue
        }

        $separator = $trimmed.IndexOf("=")
        if ($separator -lt 1) {
            throw "Invalid .env.local entry: $trimmed"
        }

        $name = $trimmed.Substring(0, $separator).Trim()
        $value = $trimmed.Substring($separator + 1).Trim()
        if (($value.StartsWith('"') -and $value.EndsWith('"')) -or
            ($value.StartsWith("'") -and $value.EndsWith("'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }

        [Environment]::SetEnvironmentVariable($name, $value, "Process")
    }
}

function Test-LocalPort {
    param(
        [Parameter(Mandatory)][string]$HostName,
        [Parameter(Mandatory)][int]$Port,
        [int]$TimeoutMilliseconds = 1000
    )

    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $connection = $client.ConnectAsync($HostName, $Port)
        return $connection.Wait($TimeoutMilliseconds) -and $client.Connected
    }
    catch {
        return $false
    }
    finally {
        $client.Dispose()
    }
}

function Assert-DependencyPort {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][int]$Port
    )

    if (-not (Test-LocalPort -HostName "localhost" -Port $Port)) {
        throw "$Name is not reachable on localhost:$Port"
    }
    Write-Host "[ready] $Name localhost:$Port"
}

function Get-PortProcessId {
    param([Parameter(Mandatory)][int]$Port)

    $connection = Get-NetTCPConnection `
        -State Listen `
        -LocalPort $Port `
        -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($connection) {
        return $connection.OwningProcess
    }
    return $null
}

function Wait-ServicePort {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][int]$Port,
        [Parameter(Mandatory)][System.Diagnostics.Process]$Process,
        [Parameter(Mandatory)][string]$LogPath
    )

    $deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-LocalPort -HostName "localhost" -Port $Port) {
            Write-Host "[started] $Name localhost:$Port"
            return
        }
        if ($Process.HasExited) {
            $tail = Get-Content -LiteralPath $LogPath -Tail 40 -ErrorAction SilentlyContinue
            throw "$Name exited during startup.`n$($tail -join [Environment]::NewLine)"
        }
        Start-Sleep -Milliseconds 500
    }

    throw "Timed out waiting for $Name on localhost:$Port. See $LogPath"
}

function Start-MavenService {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string[]]$Arguments,
        [Parameter(Mandatory)][int]$Port,
        [Parameter(Mandatory)][string]$LogPrefix
    )

    if (Test-LocalPort -HostName "localhost" -Port $Port) {
        Write-Host "[running] $Name already uses localhost:$Port"
        return $null
    }

    $stdout = Join-Path $runtimeDir "$LogPrefix.stdout.log"
    $stderr = Join-Path $runtimeDir "$LogPrefix.stderr.log"
    $process = Start-Process `
        -FilePath $script:mavenCommand.Source `
        -ArgumentList $Arguments `
        -WorkingDirectory $repoRoot `
        -WindowStyle Hidden `
        -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr `
        -PassThru

    Wait-ServicePort -Name $Name -Port $Port -Process $process -LogPath $stdout
    return $process
}

function Start-Jaeger {
    if ((Test-LocalPort -HostName "localhost" -Port 16686) -and
        (Test-LocalPort -HostName "localhost" -Port 4318)) {
        Write-Host "[running] Jaeger UI localhost:16686"
        return $null
    }

    $jaegerExecutable = Get-ChildItem `
        -LiteralPath $localJaegerDir `
        -Recurse `
        -Filter "jaeger.exe" `
        -ErrorAction SilentlyContinue |
        Select-Object -First 1

    if ($jaegerExecutable) {
        $stdout = Join-Path $runtimeDir "jaeger.stdout.log"
        $stderr = Join-Path $runtimeDir "jaeger.stderr.log"
        $process = Start-Process `
            -FilePath $jaegerExecutable.FullName `
            -WorkingDirectory $jaegerExecutable.DirectoryName `
            -WindowStyle Hidden `
            -RedirectStandardOutput $stdout `
            -RedirectStandardError $stderr `
            -PassThru
        Wait-ServicePort `
            -Name "Jaeger UI" `
            -Port 16686 `
            -Process $process `
            -LogPath $stderr
        if (-not (Test-LocalPort -HostName "localhost" -Port 4318)) {
            throw "Jaeger UI started, but OTLP HTTP port 4318 is unavailable"
        }
        Write-Host "[started] Jaeger OTLP HTTP localhost:4318"
        return $process
    }

    $dockerCommand = Get-Command "docker" -ErrorAction SilentlyContinue
    if (-not $dockerCommand) {
        throw "Jaeger is not installed under var/jaeger and Docker CLI is unavailable"
    }
    & $dockerCommand.Source compose --profile observability up -d jaeger
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to start Jaeger with Docker Compose"
    }
    return $null
}

function Start-Prometheus {
    if (Test-LocalPort -HostName "localhost" -Port 9090) {
        Write-Host "[running] Prometheus localhost:9090"
        return $null
    }

    $prometheusExecutable = Get-ChildItem `
        -LiteralPath $localPrometheusDir `
        -Recurse `
        -Filter "prometheus.exe" `
        -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $prometheusExecutable) {
        throw "Prometheus is not installed. Run scripts\install-observability.ps1 first"
    }
    if (-not (Test-Path -LiteralPath $prometheusConfig)) {
        throw "Prometheus configuration is missing: $prometheusConfig"
    }

    $dataDir = Join-Path $runtimeDir "prometheus-data"
    $stdout = Join-Path $runtimeDir "prometheus.stdout.log"
    $stderr = Join-Path $runtimeDir "prometheus.stderr.log"
    New-Item -ItemType Directory -Path $dataDir -Force | Out-Null
    $process = Start-Process `
        -FilePath $prometheusExecutable.FullName `
        -ArgumentList @(
            "--config.file=$prometheusConfig",
            "--storage.tsdb.path=$dataDir",
            "--storage.tsdb.retention.time=7d",
            "--web.listen-address=127.0.0.1:9090"
        ) `
        -WorkingDirectory $prometheusExecutable.DirectoryName `
        -WindowStyle Hidden `
        -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr `
        -PassThru
    Wait-ServicePort -Name "Prometheus" -Port 9090 -Process $process -LogPath $stderr
    return $process
}

function Start-Grafana {
    if (Test-LocalPort -HostName "localhost" -Port 3000) {
        Write-Host "[running] Grafana localhost:3000"
        return $null
    }

    $grafanaExecutable = Get-ChildItem `
        -LiteralPath $localGrafanaDir `
        -Recurse `
        -Filter "grafana.exe" `
        -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $grafanaExecutable) {
        throw "Grafana is not installed. Run scripts\install-observability.ps1 first"
    }

    $grafanaRoot = Split-Path -Parent $grafanaExecutable.DirectoryName
    $env:GF_PATHS_HOME = $grafanaRoot
    $env:GF_PATHS_DATA = Join-Path $runtimeDir "grafana-data"
    $env:GF_PATHS_LOGS = Join-Path $runtimeDir "grafana-logs"
    $env:GF_PATHS_PLUGINS = Join-Path $runtimeDir "grafana-plugins"
    $env:GF_PATHS_PROVISIONING = $grafanaProvisioningDir
    $env:GF_SERVER_HTTP_ADDR = "127.0.0.1"
    $env:GF_SERVER_HTTP_PORT = "3000"
    $env:GF_AUTH_ANONYMOUS_ENABLED = "true"
    $env:GF_AUTH_ANONYMOUS_ORG_ROLE = "Viewer"
    $env:GF_USERS_DEFAULT_THEME = "light"
    $env:AGENT_GRAFANA_DASHBOARDS_PATH = $grafanaDashboardsDir
    New-Item -ItemType Directory -Path $env:GF_PATHS_DATA -Force | Out-Null
    New-Item -ItemType Directory -Path $env:GF_PATHS_LOGS -Force | Out-Null
    New-Item -ItemType Directory -Path $env:GF_PATHS_PLUGINS -Force | Out-Null

    $stdout = Join-Path $runtimeDir "grafana.stdout.log"
    $stderr = Join-Path $runtimeDir "grafana.stderr.log"
    $process = Start-Process `
        -FilePath $grafanaExecutable.FullName `
        -ArgumentList @("server", "--homepath=$grafanaRoot") `
        -WorkingDirectory $grafanaRoot `
        -WindowStyle Hidden `
        -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr `
        -PassThru
    Wait-ServicePort -Name "Grafana" -Port 3000 -Process $process -LogPath $stderr
    return $process
}

Import-LocalEnvironment -Path $envFile
New-Item -ItemType Directory -Path $runtimeDir -Force | Out-Null

if (-not $env:JAVA_HOME) {
    $env:JAVA_HOME = "D:\develop\java\JDK\jdk-21"
}
$javaExecutable = Join-Path $env:JAVA_HOME "bin\java.exe"
if (-not (Test-Path -LiteralPath $javaExecutable)) {
    throw "Java 21 was not found under JAVA_HOME: $env:JAVA_HOME"
}
$env:Path = "$(Join-Path $env:JAVA_HOME 'bin');$env:Path"
$javaVersionInfo = [System.Diagnostics.ProcessStartInfo]::new()
$javaVersionInfo.FileName = $javaExecutable
$javaVersionInfo.Arguments = "-version"
$javaVersionInfo.UseShellExecute = $false
$javaVersionInfo.RedirectStandardOutput = $true
$javaVersionInfo.RedirectStandardError = $true
$javaVersionProcess = [System.Diagnostics.Process]::Start($javaVersionInfo)
$javaVersionOutput = $javaVersionProcess.StandardOutput.ReadToEnd() +
    $javaVersionProcess.StandardError.ReadToEnd()
$javaVersionProcess.WaitForExit()
if ($javaVersionOutput -notmatch 'version "21(?:\.|")') {
    throw "JAVA_HOME must point to Java 21. Current runtime: $($javaVersionOutput.Trim())"
}
Write-Host "[ready] Java 21 $env:JAVA_HOME"

$script:mavenCommand = Get-Command "mvn.cmd" -ErrorAction SilentlyContinue
if (-not $script:mavenCommand) {
    throw "mvn.cmd is not available on PATH"
}

if ([string]::IsNullOrWhiteSpace($env:DB_PASSWORD)) {
    throw "DB_PASSWORD is missing from .env.local"
}
if ([string]::IsNullOrWhiteSpace($env:ZHIPU_API_KEY) -and
    -not (Test-Path -LiteralPath $localProfileConfig)) {
    throw "ZHIPU_API_KEY is missing and application-local.yml was not found"
}

Assert-DependencyPort -Name "PostgreSQL" -Port 5432
Assert-DependencyPort -Name "Redis" -Port 6379

$jaegerProcess = $null
$prometheusProcess = $null
$grafanaProcess = $null
if ($Observability) {
    $jaegerProcess = Start-Jaeger
    $prometheusProcess = Start-Prometheus
    $grafanaProcess = Start-Grafana
    $env:TRACING_ENABLED = "true"
    $env:OTEL_TRACES_ENDPOINT = "http://localhost:4318/v1/traces"
}
else {
    $env:TRACING_ENABLED = "false"
}

$mcpProcess = $null
$appProcess = $null
try {
    $mcpProcess = Start-MavenService `
        -Name "Image Retrieval MCP Server" `
        -Arguments @("-f", $mcpPom, "spring-boot:run") `
        -Port 8232 `
        -LogPrefix "mcp-server"

    $appProcess = Start-MavenService `
        -Name "AI Agent application" `
        -Arguments @("spring-boot:run", "-Dspring-boot.run.profiles=local") `
        -Port 8231 `
        -LogPrefix "ai-agent"

    $state = [ordered]@{
        startedAt = (Get-Date).ToString("o")
        mcpPid = Get-PortProcessId -Port 8232
        appPid = Get-PortProcessId -Port 8231
        jaegerPid = Get-PortProcessId -Port 16686
        prometheusPid = Get-PortProcessId -Port 9090
        grafanaPid = Get-PortProcessId -Port 3000
        observability = [bool]$Observability
    }
    $state | ConvertTo-Json | Set-Content -LiteralPath $stateFile -Encoding UTF8

    Write-Host ""
    Write-Host "Local environment is ready."
    Write-Host "Application: http://localhost:8231"
    Write-Host "Health:      http://localhost:8231/api/actuator/health"
    Write-Host "MCP SSE:     http://localhost:8232/sse"
    if ($Observability) {
        Write-Host "Jaeger:      http://localhost:16686"
        Write-Host "Prometheus:  http://localhost:9090"
        Write-Host "Grafana:     http://localhost:3000/d/agent-observability"
    }
    Write-Host "Logs:        $runtimeDir"
}
catch {
    if ($appProcess -and -not $appProcess.HasExited) {
        Stop-Process -Id $appProcess.Id -Force -ErrorAction SilentlyContinue
    }
    if ($mcpProcess -and -not $mcpProcess.HasExited) {
        Stop-Process -Id $mcpProcess.Id -Force -ErrorAction SilentlyContinue
    }
    throw
}
