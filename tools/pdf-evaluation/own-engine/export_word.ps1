param(
    [Parameter(Mandatory)][string]$Source,
    [Parameter(Mandatory)][string]$Output
)
$ErrorActionPreference = 'Stop'
$sourcePath = (Resolve-Path -LiteralPath $Source).Path
$outputPath = [IO.Path]::GetFullPath($Output)
if (Test-Path -LiteralPath $outputPath) { throw 'Refuse to overwrite an existing Word reference.' }
$null = New-Item -ItemType Directory -Force -Path ([IO.Path]::GetDirectoryName($outputPath))
$word = $null
$document = $null
try {
    $word = New-Object -ComObject Word.Application
    $word.Visible = $false
    $word.DisplayAlerts = 0
    $word.AutomationSecurity = 3
    $document = $word.Documents.Open($sourcePath, $false, $true, $false)
    $document.ExportAsFixedFormat($outputPath, 17)
    @{
        exporter = 'Microsoft Word ExportAsFixedFormat'
        version = $word.Version
        build = $word.Build
        sourceSha256 = (Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash.ToLowerInvariant()
        pdfSha256 = (Get-FileHash -LiteralPath $outputPath -Algorithm SHA256).Hash.ToLowerInvariant()
    } | ConvertTo-Json | Set-Content -LiteralPath ($outputPath + '.provenance.json') -Encoding utf8
} finally {
    try {
        if ($null -ne $document) {
            try { $document.Close(0) } finally { [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($document) }
        }
    } finally {
        if ($null -ne $word) {
            try { $word.Quit(0) } finally { [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($word) }
        }
    }
}
