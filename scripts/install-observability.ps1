[CmdletBinding()]
param(
    [string]$PrometheusVersion = "3.11.0",
    [string]$GrafanaVersion = "13.1.0"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$runtimeDir = Join-Path $repoRoot "var"
$downloadDir = Join-Path $runtimeDir "observability-downloads"
$prometheusDir = Join-Path $runtimeDir "prometheus"
$grafanaDir = Join-Path $runtimeDir "grafana"

function Get-VerifiedArchive {
    param(
        [Parameter(Mandatory)][string]$Url,
        [Parameter(Mandatory)][string]$ChecksumUrl,
        [Parameter(Mandatory)][string]$ArchivePath,
        [string]$ChecksumFileName
    )

    if (-not (Test-Path -LiteralPath $ArchivePath)) {
        Write-Host "[download] $Url"
        $partialPath = "$ArchivePath.partial"
        Invoke-WebRequest -Uri $Url -OutFile $partialPath -UseBasicParsing
        Move-Item -LiteralPath $partialPath -Destination $ArchivePath
    }

    $checksumPath = "$ArchivePath.sha256"
    Invoke-WebRequest -Uri $ChecksumUrl -OutFile $checksumPath -UseBasicParsing
    $checksumText = (Get-Content -LiteralPath $checksumPath -Raw).Trim()
    if ($ChecksumFileName) {
        $matchingLine = $checksumText -split "`r?`n" |
            Where-Object { $_ -match [regex]::Escape($ChecksumFileName) } |
            Select-Object -First 1
        if (-not $matchingLine) {
            throw "Checksum for $ChecksumFileName was not found in $ChecksumUrl"
        }
        $expectedHash = ($matchingLine -split "\s+")[0]
    }
    else {
        $expectedHash = ($checksumText -split "\s+")[0]
    }

    $actualHash = (Get-FileHash -LiteralPath $ArchivePath -Algorithm SHA256).Hash
    if ($actualHash -ne $expectedHash) {
        throw "SHA256 verification failed for $ArchivePath"
    }
    Write-Host "[verified] $ArchivePath"
}

function Expand-ObservabilityArchive {
    param(
        [Parameter(Mandatory)][string]$ArchivePath,
        [Parameter(Mandatory)][string]$Destination,
        [Parameter(Mandatory)][string]$ExecutableName
    )

    $existing = Get-ChildItem -LiteralPath $Destination -Recurse -Filter $ExecutableName `
        -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($existing) {
        Write-Host "[installed] $($existing.FullName)"
        return
    }

    New-Item -ItemType Directory -Path $Destination -Force | Out-Null
    Expand-Archive -LiteralPath $ArchivePath -DestinationPath $Destination -Force
    $installed = Get-ChildItem -LiteralPath $Destination -Recurse -Filter $ExecutableName `
        -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $installed) {
        throw "$ExecutableName was not found after extracting $ArchivePath"
    }
    Write-Host "[installed] $($installed.FullName)"
}

New-Item -ItemType Directory -Path $downloadDir -Force | Out-Null

$prometheusFile = "prometheus-$PrometheusVersion.windows-amd64.zip"
$prometheusArchive = Join-Path $downloadDir $prometheusFile
Get-VerifiedArchive `
    -Url "https://github.com/prometheus/prometheus/releases/download/v$PrometheusVersion/$prometheusFile" `
    -ChecksumUrl "https://github.com/prometheus/prometheus/releases/download/v$PrometheusVersion/sha256sums.txt" `
    -ArchivePath $prometheusArchive `
    -ChecksumFileName $prometheusFile
Expand-ObservabilityArchive `
    -ArchivePath $prometheusArchive `
    -Destination $prometheusDir `
    -ExecutableName "prometheus.exe"

$grafanaFile = "grafana-$GrafanaVersion.windows-amd64.zip"
$grafanaArchive = Join-Path $downloadDir $grafanaFile
Get-VerifiedArchive `
    -Url "https://dl.grafana.com/oss/release/$grafanaFile" `
    -ChecksumUrl "https://dl.grafana.com/oss/release/$grafanaFile.sha256" `
    -ArchivePath $grafanaArchive
Expand-ObservabilityArchive `
    -ArchivePath $grafanaArchive `
    -Destination $grafanaDir `
    -ExecutableName "grafana.exe"

Write-Host ""
Write-Host "Prometheus and Grafana are installed under $runtimeDir"
