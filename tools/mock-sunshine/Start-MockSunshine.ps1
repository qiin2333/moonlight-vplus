param(
    [string]$HostName = "Mock Paired PC",
    [string]$Uuid = "MOCK-PC-PAIRING-UUID",
    [string]$HostAddressForClient = "10.0.2.2",
    [int]$HttpPort = 48089,
    [int]$HttpsPort = 48084,
    [int]$PairStatus = 0,
    [int]$HttpsServerInfoStatus = 401,
    [string[]]$AppTitles = @("Mock Desktop", "Mock Steam"),
    [switch]$Background
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$generatedDir = Join-Path $scriptDir ".generated"
New-Item -ItemType Directory -Force -Path $generatedDir | Out-Null

$opensslConfig = Join-Path $generatedDir "openssl-minimal.cnf"
if (!(Test-Path $opensslConfig)) {
    @"
[req]
distinguished_name = dn
prompt = no

[dn]
CN = mock-sunshine
"@ | Set-Content -Encoding ASCII -Path $opensslConfig
}

$env:OPENSSL_CONF = $opensslConfig

function New-TestCertificateIfMissing($KeyPath, $CertPath, $Subject) {
    if ((Test-Path $KeyPath) -and (Test-Path $CertPath)) {
        return
    }

    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & openssl req -x509 -newkey rsa:2048 -nodes `
        -keyout $KeyPath `
        -out $CertPath `
        -days 7 `
        -subj $Subject > $null 2> $null
    $opensslExitCode = $LASTEXITCODE
    $ErrorActionPreference = $previousErrorActionPreference

    if ($opensslExitCode -ne 0) {
        throw "openssl failed to create test certificate for $Subject"
    }
}

$serverKey = Join-Path $generatedDir "mock-server-key.pem"
$serverCert = Join-Path $generatedDir "mock-server-cert.pem"
$serverCertDer = Join-Path $generatedDir "mock-server-cert.der"

New-TestCertificateIfMissing $serverKey $serverCert "/CN=mock-sunshine-server"

if (!(Test-Path $serverCertDer)) {
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & openssl x509 -in $serverCert -outform DER -out $serverCertDer > $null 2> $null
    $opensslExitCode = $LASTEXITCODE
    $ErrorActionPreference = $previousErrorActionPreference

    if ($opensslExitCode -ne 0) {
        throw "openssl failed to export mock server certificate DER"
    }
}

$config = [ordered]@{
    hostName = $HostName
    uniqueId = $Uuid
    hostAddressForClient = $HostAddressForClient
    httpPort = $HttpPort
    httpsPort = $HttpsPort
    pairStatus = $PairStatus
    httpsServerInfoStatus = $HttpsServerInfoStatus
    appTitles = $AppTitles
}

$configPath = Join-Path $generatedDir "mock-config.json"
$json = $config | ConvertTo-Json
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($configPath, $json, $utf8NoBom)

$server = Join-Path $scriptDir "server.js"

if ($Background) {
    $stdout = Join-Path $generatedDir "server.out.log"
    $stderr = Join-Path $generatedDir "server.err.log"
    if (Test-Path $stdout) { Clear-Content $stdout }
    if (Test-Path $stderr) { Clear-Content $stderr }

    $process = Start-Process `
        -FilePath "node" `
        -ArgumentList $server `
        -WorkingDirectory $scriptDir `
        -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr `
        -WindowStyle Hidden `
        -PassThru

    "Started mock Sunshine PID=$($process.Id)"
    "stdout: $stdout"
    "stderr: $stderr"
    "seed cert: $serverCertDer"
    return
}

& node $server
