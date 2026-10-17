#requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Leaf })]
    [string]$AivenCaCertificatePath
)

# Prints values only to the local terminal for entry into Render. It creates no files,
# changes no provider settings, and must never be redirected to source control.
$ErrorActionPreference = 'Stop'
function New-TravelGoSecret {
    $taskBytes = New-Object byte[] 32
    $taskRandom = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $taskRandom.GetBytes($taskBytes)
        [Convert]::ToBase64String($taskBytes)
    } finally {
        $taskRandom.Dispose()
        [Array]::Clear($taskBytes, 0, $taskBytes.Length)
    }
}

$taskCertificatePath = (Resolve-Path -LiteralPath $AivenCaCertificatePath).Path
$taskCertificate = [IO.File]::ReadAllBytes($taskCertificatePath)
try {
    if ($taskCertificate.Length -lt 128 -or $taskCertificate.Length -gt 65536) {
        throw 'The supplied Aiven CA certificate has an unexpected size.'
    }
    Write-Output ('TRAVELGO_AIVEN_CA_CERT_PEM_BASE64=' + [Convert]::ToBase64String($taskCertificate))
    Write-Output ('TRAVELGO_DB_TRUSTSTORE_PASSWORD=' + (New-TravelGoSecret))
    Write-Output ('TRAVELGO_DB_PASSWORD=' + (New-TravelGoSecret))
    Write-Output ('TRAVELGO_BOOTSTRAP_ADMIN_PASSWORD=' + (New-TravelGoSecret))
    Write-Output ('TRAVELGO_AUTH_RATE_HMAC_KEY=' + (New-TravelGoSecret))
    Write-Output ('TRAVELGO_STAFF_MFA_ENCRYPTION_KEY=' + (New-TravelGoSecret))
} finally {
    [Array]::Clear($taskCertificate, 0, $taskCertificate.Length)
}
