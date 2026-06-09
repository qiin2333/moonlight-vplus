param(
    [string]$Package = "com.limelight.vplus_debug",
    [string]$DeviceSerial = "",
    [string]$HostName = "Mock Paired PC",
    [string]$Uuid = "MOCK-PC-PAIRING-UUID",
    [string]$HostAddress = "10.0.2.2",
    [int]$HttpPort = 48089,
    [int]$HttpsPort = 48084,
    [string]$CertDerPath = "",
    [switch]$RemoveOnly
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$generatedDir = Join-Path $scriptDir ".generated"
New-Item -ItemType Directory -Force -Path $generatedDir | Out-Null
if ([string]::IsNullOrWhiteSpace($CertDerPath)) {
    $CertDerPath = Join-Path $scriptDir ".generated\mock-server-cert.der"
}

if (!$RemoveOnly -and !(Test-Path $CertDerPath)) {
    throw "Missing DB certificate DER: $CertDerPath. Run Start-MockSunshine.ps1 once first."
}

$adbArgs = @()
if (![string]::IsNullOrWhiteSpace($DeviceSerial)) {
    $adbArgs += @("-s", $DeviceSerial)
}

function Invoke-Adb {
    param([string[]]$ArgumentList)
    $fullArgs = @()
    $fullArgs += $script:adbArgs
    $fullArgs += $ArgumentList
    & adb @fullArgs
}

function Invoke-AdbQuiet {
    param([string[]]$ArgumentList)
    Invoke-Adb $ArgumentList | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "adb command failed: adb $($script:adbArgs -join ' ') $($ArgumentList -join ' ')"
    }
}

function Pull-AppFile {
    param(
        [string]$RemotePath,
        [string]$LocalPath
    )

    $serialArgs = ""
    if (![string]::IsNullOrWhiteSpace($DeviceSerial)) {
        $serialArgs = "-s $DeviceSerial "
    }

    $escapedLocalPath = $LocalPath.Replace('"', '\"')
    $cmd = "adb $serialArgs exec-out run-as $Package cat $RemotePath > `"$escapedLocalPath`""
    cmd /c $cmd
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to pull app file: $RemotePath"
    }
}

$null = Get-Command python -ErrorAction Stop

$workingDb = Join-Path $generatedDir "computers-seed.db"
$payloadPath = Join-Path $generatedDir "seed-payload.json"
$remoteDb = "/data/local/tmp/mock-computers-$Package.db"

Invoke-Adb @("shell", "am", "force-stop", $Package) | Out-Null
Invoke-AdbQuiet @("shell", "run-as", $Package, "mkdir", "-p", "databases")
Invoke-Adb @("shell", "run-as", $Package, "ls", "databases/computers.db") | Out-Null
if ($LASTEXITCODE -eq 0) {
    Pull-AppFile "databases/computers.db" $workingDb
} else {
    if (Test-Path $workingDb) {
        Remove-Item -LiteralPath $workingDb -Force
    }
}

$addresses = [ordered]@{
    local = @{ address = $HostAddress; port = $HttpPort }
    manual = @{ address = $HostAddress; port = $HttpPort }
    lastActive = @{ address = $HostAddress; port = $HttpPort }
    httpsPort = $HttpsPort
    ipv6Disabled = $false
}

$payload = [ordered]@{
    hostName = $HostName
    uuid = $Uuid
    addresses = $addresses
    macAddress = "00:11:22:33:44:55"
    certDerPath = $CertDerPath
    removeOnly = [bool]$RemoveOnly
}

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($payloadPath, ($payload | ConvertTo-Json -Depth 8), $utf8NoBom)

$python = @'
import json
import os
import sqlite3
import sys

db_path = sys.argv[1]
payload_path = sys.argv[2]

with open(payload_path, "r", encoding="utf-8") as f:
    payload = json.load(f)

os.makedirs(os.path.dirname(db_path), exist_ok=True)

con = sqlite3.connect(db_path)
try:
    cur = con.cursor()
    cur.execute("PRAGMA user_version = 4")
    cur.execute("CREATE TABLE IF NOT EXISTS android_metadata (locale TEXT)")
    cur.execute("SELECT COUNT(*) FROM android_metadata")
    if cur.fetchone()[0] == 0:
        cur.execute("INSERT INTO android_metadata(locale) VALUES ('en_US')")
    cur.execute(
        "CREATE TABLE IF NOT EXISTS Computers("
        "UUID TEXT PRIMARY KEY, "
        "ComputerName TEXT NOT NULL, "
        "Addresses TEXT NOT NULL, "
        "MacAddress TEXT, "
        "ServerCert BLOB)"
    )

    cur.execute("DELETE FROM Computers WHERE UUID = ?", (payload["uuid"],))
    if not payload.get("removeOnly"):
        with open(payload["certDerPath"], "rb") as f:
            cert_der = f.read()
        addresses = json.dumps(payload["addresses"], separators=(",", ":"))
        cur.execute(
            "INSERT INTO Computers(UUID,ComputerName,Addresses,MacAddress,ServerCert) "
            "VALUES(?,?,?,?,?)",
            (
                payload["uuid"],
                payload["hostName"],
                addresses,
                payload["macAddress"],
                cert_der,
            ),
        )

    con.commit()
    rows = cur.execute(
        "SELECT UUID, ComputerName, Addresses, length(ServerCert) "
        "FROM Computers WHERE UUID = ?",
        (payload["uuid"],),
    ).fetchall()
    for row in rows:
        print("|".join("" if value is None else str(value) for value in row))
finally:
    con.close()
'@

$python | python - $workingDb $payloadPath
if ($LASTEXITCODE -ne 0) {
    throw "Failed to update local computer database"
}

Invoke-AdbQuiet @("push", $workingDb, $remoteDb)
Invoke-AdbQuiet @("shell", "chmod", "644", $remoteDb)
Invoke-AdbQuiet @("shell", "run-as", $Package, "cp", $remoteDb, "databases/computers.db")
Invoke-AdbQuiet @("shell", "run-as", $Package, "chmod", "600", "databases/computers.db")
Invoke-Adb @("shell", "run-as", $Package, "rm", "-f", "databases/computers.db-journal", "databases/computers.db-wal", "databases/computers.db-shm") | Out-Null
Invoke-Adb @("shell", "rm", "-f", $remoteDb) | Out-Null

if ($RemoveOnly) {
    "Removed mock computer $Uuid from $Package"
    return
}
