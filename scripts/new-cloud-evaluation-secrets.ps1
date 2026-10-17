#requires -Version 7.0
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
    $taskBytes = [byte[]]::new(32)
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($taskBytes)
    try { [Convert]::ToBase64String($taskBytes) }
    finally { [Array]::Clear($taskBytes, 0, $taskBytes.Length) }
}

$taskCertificate = [IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $AivenCaCertificatePath))
try {
    if ($taskCertificate.Length -lt 128 -or $taskCertificate.Length -gt 65536) {
        throw 'The supplied Aiven CA certificate has an unexpected size.'
    }
    Write-Output 'TRAVELGO_AIVEN_CA_CERT_PEM_BASE64=' + [Convert]::ToBase64String($taskCertificate)
    Write-Output 'TRAVELGO_DB_TRUSTSTORE_PASSWORD=' + (New-TravelGoSecret)
    Write-Output 'TRAVELGO_DB_PASSWORD=' + (New-TravelGoSecret)
    Write-Output 'TRAVELGO_BOOTSTRAP_ADMIN_PASSWORD=' + (New-TravelGoSecret)
    Write-Output 'TRAVELGO_AUTH_RATE_HMAC_KEY=' + (New-TravelGoSecret)
    Write-Output 'TRAVELGO_STAFF_MFA_ENCRYPTION_KEY=' + (New-TravelGoSecret)
} finally {
    [Array]::Clear($taskCertificate, 0, $taskCertificate.Length)
}
