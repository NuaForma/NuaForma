param([Parameter(Mandatory)][string]$Source, [Parameter(Mandatory)][string]$Directory, [switch]$CanonicalCopy)
# Optional interoperability oracle for trusted synthetic fixtures, never production.
$ErrorActionPreference = 'Stop'
$sourcePath = (Resolve-Path -LiteralPath $Source).Path
if (Test-Path -LiteralPath $Directory) { throw 'Oracle directory already exists.' }
[void](New-Item -ItemType Directory -Path $Directory)
$target = (Resolve-Path -LiteralPath $Directory).Path
$before = (Get-FileHash -LiteralPath $sourcePath).Hash
$word = $null
$document = $null
try {
    $word = New-Object -ComObject Word.Application
    $word.Visible = $false
    $word.DisplayAlerts = 0
    $word.AutomationSecurity = 3
    $document = $word.Documents.Open($sourcePath, $false, $true, $false)
    $document.Repaginate()
    $shapes = @()
    foreach ($shape in $document.Shapes) {
        $shapes += [ordered]@{
            name = $shape.Name; left = $shape.Left; top = $shape.Top
            width = $shape.Width; height = $shape.Height; rotation = $shape.Rotation
            horizontalRelative = $shape.RelativeHorizontalPosition
            verticalRelative = $shape.RelativeVerticalPosition
            textOrientation = $shape.TextFrame.Orientation
            textMarginLeft = $shape.TextFrame.MarginLeft
            textMarginTop = $shape.TextFrame.MarginTop
            textMarginRight = $shape.TextFrame.MarginRight
            textMarginBottom = $shape.TextFrame.MarginBottom
            wrapType = $shape.WrapFormat.Type
            wrapSide = $shape.WrapFormat.Side
            allowOverlap = $shape.WrapFormat.AllowOverlap
            layoutInCell = $shape.LayoutInCell
            anchorHorizontal = $shape.Anchor.Information(5)
            anchorVertical = $shape.Anchor.Information(6)
        }
    }
    $shapes | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath "$target/shapes.json"
    $document.ExportAsFixedFormat("$target/reference.pdf", 17)
    # Save only a new private oracle copy, to inspect Word's canonical serialization.
    if ($CanonicalCopy) { $document.SaveAs2("$target/word-normalized.docx", 16) }
} finally {
    if ($document) { $document.Close(0); [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($document) }
    if ($word) { $word.Quit(); [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($word) }
}
if ((Get-FileHash -LiteralPath $sourcePath).Hash -ne $before) { throw 'Oracle changed source.' }
Write-Output "Source unchanged; Word oracle saved in $target"
