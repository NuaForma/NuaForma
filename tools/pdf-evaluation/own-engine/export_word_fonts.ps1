param(
    [Parameter(Mandatory=$true)][string]$Source,
    [Parameter(Mandatory=$true)][string]$Output,
    [Parameter(Mandatory=$true)][string[]]$FontPaths
)
$ErrorActionPreference = 'Stop'
if (-not ('NuaFormaFixtureFonts' -as [type])) {
    Add-Type @'
using System.Runtime.InteropServices;
public static class NuaFormaFixtureFonts {
    [DllImport("gdi32.dll", CharSet = CharSet.Unicode)]
    public static extern int AddFontResourceEx(string path, uint flags, System.IntPtr reserved);
    [DllImport("gdi32.dll", CharSet = CharSet.Unicode)]
    public static extern bool RemoveFontResourceEx(string path, uint flags, System.IntPtr reserved);
}
'@
}
$activated = @()
try {
    foreach ($font in $FontPaths) {
        $resolved = (Resolve-Path -LiteralPath $font).Path
        if ([NuaFormaFixtureFonts]::AddFontResourceEx($resolved, 0, [IntPtr]::Zero) -eq 0) {
            throw 'Cannot activate the explicitly supplied fixture font'
        }
        $activated += $resolved
    }
    & (Join-Path $PSScriptRoot 'export_word.ps1') -Source $Source -Output $Output
    $receiptPath = (Resolve-Path -LiteralPath $Output).Path + '.provenance.json'
    $receipt = Get-Content -LiteralPath $receiptPath -Raw | ConvertFrom-Json
    $fontHashes = @($activated | ForEach-Object { (Get-FileHash -Algorithm SHA256 -LiteralPath $_).Hash.ToLowerInvariant() })
    $receipt | Add-Member -NotePropertyName fontSha256 -NotePropertyValue $fontHashes
    $receipt | ConvertTo-Json | Set-Content -LiteralPath $receiptPath -Encoding utf8
} finally {
    foreach ($font in $activated) {
        if (-not [NuaFormaFixtureFonts]::RemoveFontResourceEx($font, 0, [IntPtr]::Zero)) {
            Write-Warning 'The fixture font could not be deactivated'
        }
    }
}
