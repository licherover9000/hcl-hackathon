# RecoverPlus: one command to install everything and run the full app on Windows.
#   powershell -ExecutionPolicy Bypass -File scripts\run_local.ps1
#
# Installs portable copies (no admin, nothing added to PATH) of Java 21, Maven, Node 22 and
# PostgreSQL 16 into %USERPROFILE%\.recoverplus. The database runs on port 5433 so it never
# clashes with another PostgreSQL. Each service opens in its own window so you can see its log.
# To stop: press Ctrl+C in each window (database window last). Running the script again
# skips the downloads and starts only what is not already running.

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$Repo  = Split-Path -Parent $PSScriptRoot
$Home2 = Join-Path $env:USERPROFILE '.recoverplus'
$Tools = Join-Path $Home2 'tools'
$PgData = Join-Path $Home2 'pgdata'
$PgPort = 5433
New-Item -ItemType Directory -Force -Path $Tools | Out-Null

function Say($m) { Write-Host "==> $m" -ForegroundColor Cyan }

function Test-Port($port) {
    try { $c = New-Object Net.Sockets.TcpClient; $c.Connect('127.0.0.1', $port); $c.Close(); return $true } catch { return $false }
}

function Wait-Port($port, $seconds) {
    for ($i = 0; $i -lt $seconds; $i++) { if (Test-Port $port) { return $true }; Start-Sleep -Seconds 1 }
    return (Test-Port $port)
}

function Start-Window($title, $workDir, $command) {
    Start-Process powershell -WorkingDirectory $workDir -ArgumentList '-NoExit', '-Command',
        "`$Host.UI.RawUI.WindowTitle='$title'; $command"
}

function Get-Portable($name, $urls, $marker) {
    $dest = Join-Path $Tools $name
    $found = Get-ChildItem -Path $dest -Recurse -Filter $marker -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($found) { return $found.Directory.FullName }
    Say "Downloading $name (one time only)"
    $zip = Join-Path $Tools "$name.zip"
    $ok = $false
    foreach ($u in $urls) {
        try {
            Invoke-WebRequest -Uri $u -OutFile $zip -UseBasicParsing
            if ((Get-Item $zip).Length -gt 5MB) { $ok = $true; break }
        } catch { Write-Host "    failed: $u" }
    }
    if (-not $ok) { throw "Could not download $name. Check your internet connection and run the script again." }
    Unblock-File $zip
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    Say "Extracting $name"
    if (Get-Command tar.exe -ErrorAction SilentlyContinue) { tar.exe -xf $zip -C $dest } else { Expand-Archive $zip -DestinationPath $dest -Force }
    Remove-Item $zip
    $found = Get-ChildItem -Path $dest -Recurse -Filter $marker | Select-Object -First 1
    if (-not $found) { throw "$name extracted but $marker was not found in $dest" }
    return $found.Directory.FullName
}

# ---------- tools ----------
$PgBin = Get-Portable 'postgresql16' @(
    'https://sbp.enterprisedb.com/getfile.jsp?fileid=1260623',
    'https://get.enterprisedb.com/postgresql/postgresql-16.4-1-windows-x64-binaries.zip') 'postgres.exe'
$JavaBin = Get-Portable 'jdk21' @('https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse') 'javac.exe'
$JavaHome = Split-Path -Parent $JavaBin
$MvnBin  = Get-Portable 'maven' @(
    'https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.zip',
    'https://dlcdn.apache.org/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.zip') 'mvn.cmd'
# Vite needs Node 20.19+ or 22.12+.
$NodeDir = Get-Portable 'node22' @('https://nodejs.org/dist/v22.14.0/node-v22.14.0-win-x64.zip') 'node.exe'

# ---------- python (the system one, not a venv: Application Control blocks venv exes) ----------
$Py = $null
if ($env:VIRTUAL_ENV) {
    $base = & python -c "import sys; print(sys.base_prefix)"
    if (Test-Path "$base\python.exe") { $Py = "$base\python.exe" }
}
if (-not $Py) {
    if (Get-Command py -ErrorAction SilentlyContinue) { $Py = (& py -3 -c "import sys; print(sys.executable)") }
    elseif (Get-Command python -ErrorAction SilentlyContinue) { $Py = (Get-Command python).Source }
    else { throw 'Python not found. Install Python 3.11+ from python.org (tick "Add to PATH") and run again.' }
}
Say "Python: $Py"

# ---------- data pack ----------
$DataDir = Join-Path $Repo 'data\input'
if (-not (Get-ChildItem $DataDir -Filter '*.csv' -ErrorAction SilentlyContinue)) {
    throw "No CSV files in $DataDir. Extract the HCLTech case_03 data pack there and run again."
}

# ---------- database ----------
if (-not (Test-Path "$PgData\PG_VERSION")) {
    Say 'Creating the database files (one time only)'
    $pw = Join-Path $Home2 'pw.txt'
    Set-Content -Path $pw -Value 'recoverplus' -NoNewline -Encoding ascii
    & "$PgBin\initdb.exe" -D $PgData -U recoverplus --pwfile=$pw -E UTF8 --auth=scram-sha-256 | Out-Host
    Remove-Item $pw
}
if (-not (Test-Port $PgPort)) {
    # pg_ctl.exe is blocked by Application Control on this laptop, so postgres.exe runs directly.
    Say "Starting PostgreSQL on port $PgPort"
    Start-Window "RecoverPlus database :$PgPort" $Home2 "& '$PgBin\postgres.exe' -D '$PgData' -p $PgPort"
    if (-not (Wait-Port $PgPort 30)) { throw 'PostgreSQL did not start. Look at the "RecoverPlus database" window and send its error.' }
    Start-Sleep -Seconds 2
}
$env:PGPASSWORD = 'recoverplus'
$exists = & "$PgBin\psql.exe" -h localhost -p $PgPort -U recoverplus -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='recoverplus'"
if ("$exists".Trim() -ne '1') {
    & "$PgBin\createdb.exe" -h localhost -p $PgPort -U recoverplus recoverplus
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the recoverplus database. Send the error above.' }
}
Say 'Database ready'

# ---------- ML service ----------
if (-not (Test-Port 8001)) {
    Say 'Installing ML packages (quick after the first time)'
    & $Py -m pip install --user --disable-pip-version-check -q -r (Join-Path $Repo 'ml\requirements.txt')
    Start-Window 'RecoverPlus ML :8001' $Repo "& '$Py' -m uvicorn serve:app --app-dir ml --port 8001"
}

# ---------- backend ----------
if (-not (Test-Port 8080)) {
    Say 'Starting the backend'
    Start-Window 'RecoverPlus backend :8080' (Join-Path $Repo 'backend') (
        "`$env:JAVA_HOME='$JavaHome'; `$env:Path='$JavaBin;$MvnBin;' + `$env:Path; " +
        "`$env:DB_URL='jdbc:postgresql://localhost:$PgPort/recoverplus'; " +
        "`$env:DATA_DIR='$DataDir'; `$env:OUTPUT_DIR='$(Join-Path $Repo 'output')'; " +
        "mvn spring-boot:run")
}

# ---------- frontend ----------
if (-not (Test-Port 5173)) {
    Say 'Starting the frontend'
    Start-Window 'RecoverPlus frontend :5173' (Join-Path $Repo 'frontend') (
        "`$env:Path='$NodeDir;' + `$env:Path; npm install; npm run dev")
}

# ---------- wait for the backend ----------
Say 'Waiting for the backend (first run can take a few minutes)'
$up = $false
for ($i = 0; $i -lt 120; $i++) {
    try { Invoke-RestMethod http://localhost:8080/api/health -TimeoutSec 3 | Out-Null; $up = $true; break } catch { Start-Sleep -Seconds 5 }
}
if ($up) {
    Say 'All running. Opening http://localhost:5173  (login U601 / demo123)'
    Start-Process 'http://localhost:5173'
} else {
    Write-Host 'Backend did not come up in 10 minutes. Look at the "RecoverPlus backend" window and send the red error lines.' -ForegroundColor Yellow
}
