# TravelGo - Spring Boot Run Script
# Use the caller's installed JDK instead of a machine-specific IDE path.
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    if ($env:JAVA_HOME -and -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
        throw 'JAVA_HOME does not point to an installed JDK. Set it to JDK 17 or newer.'
    }
    if (-not $env:JAVA_HOME -and -not (Get-Command java -ErrorAction SilentlyContinue)) {
        throw 'Install JDK 17 or newer and set JAVA_HOME or add java to PATH.'
    }
    & '.\mvnw.cmd' spring-boot:run @args
    $runExitCode = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $runExitCode
