# Compiles the app together with the self-tests and runs them. Exit code is non-zero on failure.
#
#   powershell -ExecutionPolicy Bypass -File test\run.ps1 [-Jdk <jdk folder>]
#
# The JDK defaults to the project's pinned one, then JAVA_HOME. It must be a full JDK
# (the tests use jdk.httpserver and keytool), not the app's trimmed runtime.
param([string]$Jdk = "")

$ErrorActionPreference = "Stop"

if ($Jdk -eq "") {
    $Jdk = "C:\Soft\jdk-25.0.5.0.2+1"
    if (-not (Test-Path "$Jdk\bin\javac.exe") -and $env:JAVA_HOME) { $Jdk = $env:JAVA_HOME }
}
if (-not (Test-Path "$Jdk\bin\javac.exe")) {
    [Console]::Error.WriteLine("No JDK found at '$Jdk'. Pass -Jdk <folder> or set JAVA_HOME.")
    exit 2
}

# Paths are relative to the repo root, so the script works from any current folder.
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root "build\test"
if (Test-Path $out) { Remove-Item -Recurse -Force $out }

& "$Jdk\bin\javac.exe" -Xlint:all,-serial -d $out (Join-Path $root "PortableRestClient.java") (Join-Path $root "test\SelfTest.java")
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

& "$Jdk\bin\java.exe" -cp $out SelfTest
exit $LASTEXITCODE
