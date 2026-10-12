param([Parameter(Mandatory)][string]$Directory, [Parameter(Mandatory)][string]$LibreOffice, [Parameter(Mandatory)][string]$PopplerDirectory)
# Owned, bounded process control for trusted diagnostic copies only.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $Directory).Path
$output = Join-Path $root 'render'
if (Test-Path -LiteralPath $output) { throw 'Render directory exists.' }
[void](New-Item -ItemType Directory -Path "$output/profile")
$inputs = @(Get-ChildItem -LiteralPath $root -Filter '*.docx')
$hashes = @{}
$info = [Diagnostics.ProcessStartInfo]::new($LibreOffice)
$info.UseShellExecute = $false
$info.CreateNoWindow = $true
$info.RedirectStandardOutput = $true
$info.RedirectStandardError = $true
foreach ($argument in @("-env:UserInstallation=$([uri]::new("$output/profile").AbsoluteUri)", '--headless', '--nologo', '--nodefault', '--norestore', '--convert-to', 'pdf:writer_pdf_Export', '--outdir', $output)) { $info.ArgumentList.Add($argument) }
foreach ($fixture in $inputs) {
    $hashes[$fixture.FullName] = (Get-FileHash -LiteralPath $fixture.FullName).Hash
    $info.ArgumentList.Add($fixture.FullName)
}
$process = [Diagnostics.Process]::new()
$process.StartInfo = $info
try {
    [void]$process.Start()
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    if (-not $process.WaitForExit(100000)) { $process.Kill($true); $process.WaitForExit(); throw 'Research process timeout.' }
    $stdout.GetAwaiter().GetResult() + $stderr.GetAwaiter().GetResult() | Set-Content -LiteralPath "$output/process.log"
    if ($process.ExitCode -ne 0) { throw 'Research conversion failed.' }
} finally { $process.Dispose() }
foreach ($fixture in $inputs) {
    if ((Get-FileHash -LiteralPath $fixture.FullName).Hash -ne $hashes[$fixture.FullName]) { throw 'Source changed.' }
    $pdf = Join-Path $output "$($fixture.BaseName).pdf"
    if (-not (Test-Path -LiteralPath $pdf)) { throw "Missing $pdf" }
    & "$PopplerDirectory/pdftoppm.exe" -r 150 -png $pdf "$output/$($fixture.BaseName)"
    if ($LASTEXITCODE -ne 0) { throw 'Rasterization failed.' }
}
Write-Output "Rendered $($inputs.Count) unchanged sources."
