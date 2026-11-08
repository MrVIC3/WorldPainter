[CmdletBinding()]
param(
    [Parameter(Mandatory, Position = 0)]
    [ValidateSet('preview', 'build', 'generate', 'sample', 'export')]
    [string]$Mode,

    [Parameter(Mandatory, Position = 1)]
    [string]$InputFile,

    [Parameter(Mandatory, Position = 2)]
    [string]$OutputDir,

    [string]$Runtime = $env:WORLDPAINTER_HOME,

    [ValidatePattern('^[1-9][0-9]*[mMgG]$')]
    [string]$Heap = '3g',

    [ValidateRange(0, 128)]
    [int]$ExportThreads = 0,

    [Nullable[int]]$AtX,

    [Nullable[int]]$AtZ,

    [ValidateRange(0, 2)]
    [int]$TileRadius = 0
)

$ErrorActionPreference = 'Stop'
if (!$Runtime) {
    $localPath = Join-Path (Split-Path $PSScriptRoot) 'runtime.path'
    if (Test-Path -LiteralPath $localPath -PathType Leaf) {
        $Runtime = (Get-Content -LiteralPath $localPath -Raw).Trim()
    }
}
if (!$Runtime) {
    throw 'Pass -Runtime or set WORLDPAINTER_HOME to the WorldPainter directory containing lib.'
}
$lib = Join-Path $Runtime 'lib'
if (!(Test-Path -LiteralPath (Join-Path $lib 'WPCore.jar') -PathType Leaf)) {
    throw "WorldPainter lib/WPCore.jar was not found under $Runtime."
}
$java = (Get-Command java -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
$logback = Join-Path $PSScriptRoot 'logback.xml'
$javaArgs = @("-Xmx$Heap", '-Djava.awt.headless=true', "-Dlogback.configurationFile=$logback")
if ($ExportThreads -gt 0) {
    $javaArgs += "-Dorg.pepsoft.worldpainter.threads=$ExportThreads"
}
$javaArgs += @('-cp', (Join-Path $lib '*'), (Join-Path $PSScriptRoot 'WorldPainterWorlds.java'),
    $Mode.ToLowerInvariant(), $InputFile, $OutputDir)
if ($Mode -eq 'sample') {
    if ($null -eq $AtX -or $null -eq $AtZ) {
        throw 'Sample requires -AtX and -AtZ in Minecraft block coordinates.'
    }
    $javaArgs += @("$AtX", "$AtZ", "$TileRadius")
}
& $java @javaArgs
exit $LASTEXITCODE
