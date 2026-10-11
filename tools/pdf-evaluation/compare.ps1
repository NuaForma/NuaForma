param(
    [Parameter(Mandatory)][string]$LibreOffice,
    [Parameter(Mandatory)][string]$PopplerDirectory,
    [Parameter(Mandatory)][string]$WslRepository,
    [string]$WslDistribution = 'Ubuntu',
    [string[]]$WslFonts = @(),
    [string]$RunName = 'comparison',
    [string[]]$FixtureNames = @()
)
$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path "$PSScriptRoot/../..").Path
if ($RunName -notmatch '^[a-zA-Z0-9-]+$') { throw 'Invalid run name.' }
$root = Join-Path $repository "tools/pdf-evaluation/build/$RunName"
$fixtures = [ordered]@{
    'roundtrip-before' = 'docx-reference/before'
    'roundtrip-after' = 'docx-reference/after'
    'complex-expected' = 'docx-generation-reference/complex-expected'
    'complex-generated' = 'docx-generation-reference/complex-generated'
    'barcode-expected' = 'font-reference/expected'
    'barcode-generated' = 'font-reference/generated'
    'barcode-embedded' = 'font-reference/embedded'
    'barcode-isolated' = 'font-reference/isolated-embedded'
    'watermark-plain' = 'watermark-reference/plain'
    'watermark-concept' = 'watermark-reference/concept'
    'watermark-dynamic' = 'watermark-reference/dynamic'
    'watermark-vertical' = 'watermark-reference/vertical'
    'watermark-transparent' = 'watermark-reference/transparent'
    'watermark-opaque' = 'watermark-reference/opaque'
}
foreach ($name in $FixtureNames) {
    if ($name -notin $fixtures.Keys) { throw "Unknown synthetic fixture: $name" }
}
function Invoke-OwnedProcess([string]$Executable, [string[]]$Arguments, [string]$Log) {
    $info = [System.Diagnostics.ProcessStartInfo]::new($Executable)
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    foreach ($argument in $Arguments) { $info.ArgumentList.Add($argument) }
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $info
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        [void]$process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(100000)) {
            $process.Kill($true)
            $process.WaitForExit()
            throw 'Research converter exceeded 100 seconds.'
        }
        $watch.Stop()
        $stdout.GetAwaiter().GetResult() + $stderr.GetAwaiter().GetResult() | Set-Content -LiteralPath $Log
        return @{ exitCode = $process.ExitCode; seconds = $watch.Elapsed.TotalSeconds }
    } finally { $process.Dispose() }
}
New-Item -ItemType Directory -Force $root | Out-Null
$results = @()
foreach ($entry in $fixtures.GetEnumerator()) {
    if ($FixtureNames.Count -gt 0 -and $entry.Key -notin $FixtureNames) { continue }
    $source = Join-Path $repository "nuaforma-docx/build/$($entry.Value).docx"
    if (-not (Test-Path -LiteralPath $source)) { throw "Missing synthetic fixture: $source" }
    foreach ($backend in @('libreoffice', 'fop')) {
        $directory = Join-Path $root "$backend/$($entry.Key)"
        if (Test-Path -LiteralPath $directory) { throw "Output already exists: $directory" }
        New-Item -ItemType Directory -Path "$directory/profile" -Force | Out-Null
        $pdf = Join-Path $directory "$([IO.Path]::GetFileNameWithoutExtension($source)).pdf"
        if ($backend -eq 'libreoffice') {
            $profile = [uri]::new((Join-Path $directory 'profile'))
            $result = Invoke-OwnedProcess $LibreOffice @("-env:UserInstallation=$($profile.AbsoluteUri)", '--headless', '--nologo', '--nodefault', '--norestore', '--convert-to', 'pdf:writer_pdf_Export', '--outdir', $directory, $source) "$directory/process.log"
        } else {
            $wslOutput = "$WslRepository/tools/pdf-evaluation/build/$RunName/fop/$($entry.Key)"
            $result = Invoke-OwnedProcess 'wsl.exe' (@('-d', $WslDistribution, '--exec', '/usr/bin/timeout', '--kill-after=5s', '90s', '/usr/bin/time', '-v', '/usr/bin/env', "JAVA_OPTS=-Duser.home=$wslOutput/profile -Djava.io.tmpdir=$wslOutput/profile", '/bin/bash', "$WslRepository/tools/pdf-evaluation/build/install/nuaforma-pdf-evaluation/bin/nuaforma-pdf-evaluation", "$WslRepository/nuaforma-docx/build/$($entry.Value).docx", "$wslOutput/$([IO.Path]::GetFileNameWithoutExtension($source)).pdf") + $WslFonts) "$directory/process.log"
        }
        $record = [ordered]@{ fixture = $entry.Key; backend = $backend; sourceSha256 = (Get-FileHash -LiteralPath $source).Hash; exitCode = $result.exitCode; seconds = [math]::Round($result.seconds, 3); pdf = $pdf; produced = (Test-Path -LiteralPath $pdf) }
        if ($record.produced) {
            & "$PopplerDirectory/pdfinfo.exe" $pdf | Set-Content "$directory/pdfinfo.txt"
            & "$PopplerDirectory/pdftoppm.exe" -r 100 -png $pdf "$directory/page"
            if ($LASTEXITCODE -ne 0) { throw 'PDF rasterization failed.' }
        }
        $results += $record
        Write-Output "$backend $($entry.Key): exit=$($record.exitCode), PDF=$($record.produced), $($record.seconds)s"
        $results | ConvertTo-Json | Set-Content "$root/results.json"
    }
}
