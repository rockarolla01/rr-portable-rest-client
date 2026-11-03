<#
.SYNOPSIS
  Builds RestClient: compiles, runs the self-tests, creates a jar and a standalone app image.

.DESCRIPTION
  Output:
    dist\restclient.jar          runs on any Java 25+ runtime:  java -jar restclient.jar
    dist\RestClient\RestClient.exe   standalone app with its own trimmed Java runtime (no Java install needed)

.PARAMETER Jdk
  JDK 25 home. Defaults to $env:RESTCLIENT_JDK, then C:\Soft\jdk-25.0.5.0.2+1.

.PARAMETER SkipTests
  Don't run --selftest.

.PARAMETER JarOnly
  Build only the jar; skip jpackage.
#>
param(
    [string]$Jdk = $(if ($env:RESTCLIENT_JDK) { $env:RESTCLIENT_JDK } else { 'C:\Soft\jdk-25.0.5.0.2+1' }),
    [switch]$SkipTests,
    [switch]$JarOnly
)

$ErrorActionPreference = 'Stop'
$bin = Join-Path $Jdk 'bin'
if (-not (Test-Path (Join-Path $bin 'javac.exe'))) { throw "No JDK found at $Jdk (pass -Jdk or set RESTCLIENT_JDK)" }

function Invoke-Tool([string]$Name, [string[]]$Arguments) {
    & (Join-Path $bin "$Name.exe") @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Name failed with exit code $LASTEXITCODE" }
}

Push-Location $PSScriptRoot
# Workspaces saved next to the built jar/exe survive a rebuild: moved aside now, restored in finally.
$dataDirs = @('dist\data', 'dist\RestClient\data')
$stash = Join-Path ([IO.Path]::GetTempPath()) ("restclient-data-" + [Guid]::NewGuid())
try {
    foreach ($d in $dataDirs) {
        if (Test-Path $d) {
            New-Item -ItemType Directory -Force (Split-Path (Join-Path $stash $d)) | Out-Null
            Move-Item $d (Join-Path $stash $d)
        }
    }
    Remove-Item -Recurse -Force build, dist -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force build\classes, build\jar, dist | Out-Null

    Write-Host '== Compiling'
    Invoke-Tool javac @('--release', '25', '-encoding', 'UTF-8', '-Xlint:all,-serial', '-d', 'build\classes', 'StandaloneRestClient.java')

    if (-not $SkipTests) {
        Write-Host '== Self-tests'
        Invoke-Tool java @('-cp', 'build\classes', 'StandaloneRestClient', '--selftest')
    }

    Write-Host '== Jar'
    Invoke-Tool jar @('--create', '--file', 'build\jar\restclient.jar', '--main-class', 'StandaloneRestClient', '-C', 'build\classes', '.')
    Copy-Item build\jar\restclient.jar dist\

    if (-not $JarOnly) {
        # jdk.charsets: response bodies in non-UTF charsets (e.g. windows-1251, Shift_JIS).
        Write-Host '== App image (jpackage)'
        Invoke-Tool jpackage @(
            '--type', 'app-image',
            '--name', 'RestClient',
            '--app-version', '1.0',
            '--input', 'build\jar',
            '--main-jar', 'restclient.jar',
            '--main-class', 'StandaloneRestClient',
            '--add-modules', 'java.desktop,java.net.http,jdk.charsets',
            '--dest', 'dist')
    }

    Write-Host ''
    Write-Host 'Done:'
    Write-Host "  dist\restclient.jar"
    if (-not $JarOnly) { Write-Host "  dist\RestClient\RestClient.exe" }
}
finally {
    foreach ($d in $dataDirs) {
        $saved = Join-Path $stash $d
        if (Test-Path $saved) {
            New-Item -ItemType Directory -Force (Split-Path $d) | Out-Null
            Move-Item $saved $d
            Write-Host "  kept workspace data: $d"
        }
    }
    Remove-Item -Recurse -Force $stash -ErrorAction SilentlyContinue
    Pop-Location
}
