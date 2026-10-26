param(
    [Parameter(Mandatory)][string]$Source,
    [Parameter(Mandatory)][string]$Output,
    [switch]$UpdateFields
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
    if ($UpdateFields) {
        # Qualification inputs are synthetic, inert fields. Never persist Word's updated caches to the source.
        for ($pass = 0; $pass -lt 3; $pass++) {
            # TOC.Update owns its generated nested fields and bookmarks. Updating those individually
            # while rebuilding the TOC can invalidate their previous generated destinations.
            foreach ($field in $document.Fields) {
                if ($field.Type -eq 13) { continue }
                $insideContents = $false
                foreach ($contents in $document.TablesOfContents) {
                    if ($field.Code.Start -ge $contents.Range.Start -and $field.Code.End -le $contents.Range.End) { $insideContents = $true; break }
                }
                if (-not $insideContents -and -not $field.Update()) { throw 'Word could not update a source field.' }
            }
            foreach ($contents in $document.TablesOfContents) { $contents.Update() }
            $document.Repaginate()
        }
    }
    $document.ExportAsFixedFormat($outputPath, 17)
    @{
        exporter = 'Microsoft Word ExportAsFixedFormat'
        version = $word.Version
        build = $word.Build
        updatedFields = [bool]$UpdateFields
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
