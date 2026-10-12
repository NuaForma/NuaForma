param(
    [Parameter(Mandatory=$true)][string]$Destination,
    [string]$Image = 'nuaforma-pdf-runtime-qualification:11-pinned',
    [string]$SourceDirectory = (Join-Path $PSScriptRoot 'build/explicit-defaults-copies-v2'),
    [ValidateSet('probe','convert')][string]$Mode = 'probe'
)
$ErrorActionPreference = 'Stop'
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$destinationPath = [IO.Path]::GetFullPath($Destination)
$sourceDirectoryPath = [IO.Path]::GetFullPath($SourceDirectory)
if (Test-Path -LiteralPath $destinationPath) { throw 'Use a fresh qualification directory' }
New-Item -ItemType Directory -Path $destinationPath | Out-Null
$fixtures = if ($Mode -eq 'probe') { @('probe') } else {
    @(Get-ChildItem -LiteralPath $sourceDirectoryPath -Filter '*.docx' | Sort-Object Name | ForEach-Object { $_.BaseName })
}
$results = @()
foreach ($fixture in $fixtures) {
    $work = Join-Path $destinationPath $fixture
    New-Item -ItemType Directory -Path $work | Out-Null
    foreach ($part in @('output','fonts','home','cache','profile','tmp')) {
        New-Item -ItemType Directory -Path (Join-Path $work $part) | Out-Null
    }
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'runtime-fonts.conf') -Destination (Join-Path $work 'fonts.conf')
    $sourceHash = $null
    if ($Mode -eq 'convert') {
        $source = Join-Path $sourceDirectoryPath "$fixture.docx"
        $sourceHash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
        Copy-Item -LiteralPath $source -Destination (Join-Path $work 'input.docx')
        Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'build/euro-fonts-v1') -Filter '*.ttf' | ForEach-Object {
            Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $work 'fonts')
        }
    }
    $name = 'nuaforma-pdf-qualification-' + [Guid]::NewGuid().ToString('N')
    $label = 'nuaforma.qualification=' + $name
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $arguments = @('run','--rm','--init','--name',$name,'--label',$label,'--read-only','--cap-drop','ALL',
        '--security-opt','no-new-privileges','--security-opt',('seccomp=' + (Join-Path $repository 'deployment/pdf-runtime/seccomp.json')),
        '--pids-limit','128','--memory','2g','--cpus','2','--tmpfs','/tmp:rw,size=512m,mode=1777','--mount',("type=bind,source=$work,target=/request"),
        '--entrypoint','/bin/sh', $Image,'-c',
        '/opt/nuaforma/bin/render-pdf "$1" "$2" "$3" "$4" && if [ "$4" = convert ]; then exec /opt/nuaforma/bin/render-pdf "$1" "$2" "$3" validate; fi',
        'nf-qualification','/request','134217728','120',$Mode)
    $owned = Start-Job -ScriptBlock { param($arguments) & docker @arguments 2>&1; $global:LASTEXITCODE } -ArgumentList (, $arguments)
    try {
        $completed = Wait-Job -Job $owned -Timeout 150
        if ($null -eq $completed) { throw 'Owned qualification conversion exceeded its deadline' }
        $log = @(Receive-Job -Job $owned)
        $exitCode = [int]$log[-1]
        $log[0..($log.Count - 2)] | Set-Content -LiteralPath (Join-Path $work 'converter.log')
        if ($exitCode -ne 0) { throw "Qualification conversion failed for $fixture; inspect the private converter log" }
        if ($Mode -eq 'convert') {
            if ($sourceHash -ne (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash) { throw 'Source document changed' }
            $pdf = Join-Path $work 'output/input.pdf'
            if (!(Test-Path -LiteralPath $pdf)) { throw 'Converter returned success without PDF output' }
        }
        $results += @{fixture=$fixture; elapsedSeconds=$timer.Elapsed.TotalSeconds; sourceSha256=$sourceHash; exitCode=$exitCode}
    } finally {
        $owner = & docker inspect --format '{{index .Config.Labels "nuaforma.qualification"}}' $name 2>$null
        if ($LASTEXITCODE -eq 0 -and $owner -eq $name) { & docker rm --force $name | Out-Null }
        if ($owned.State -eq 'Running') { Stop-Job -Job $owned }
        Remove-Job -Job $owned
    }
}
$results | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $destinationPath 'results.json')
$results | ConvertTo-Json -Depth 10
