<#
.SYNOPSIS
  Gate jar check (docs/MILESTONES.md, common gate): exactly one mod jar; it contains fabric.mod.json,
  META-INF/neoforge.mods.toml and one mixin config; bundled libraries are relocated; no self-test class
  is in the jar. Prints one line per item and exits non-zero if any item fails.
#>
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$libs = Join-Path $root 'build\libs'
$script:failures = 0

function Report([bool]$ok, [string]$name, [string]$detail) {
    if (-not $ok) { $script:failures++ }
    Write-Output ('{0}  {1}  {2}' -f $(if ($ok) { 'PASS' } else { 'FAIL' }), $name, $detail)
}

$jars = @()
if (Test-Path $libs) { $jars = @(Get-ChildItem $libs -Filter '*.jar' -File) }
Report ($jars.Count -eq 1) 'one-jar' ('{0} jar(s) in build/libs: {1}' -f $jars.Count, (($jars | ForEach-Object { $_.Name }) -join ', '))
if ($jars.Count -ne 1) { exit 1 }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($jars[0].FullName)
try { $names = @($zip.Entries | ForEach-Object { $_.FullName }) } finally { $zip.Dispose() }

function Count-Under([string]$prefix) {
    return @($names | Where-Object { $_.StartsWith($prefix) -and -not $_.EndsWith('/') }).Count
}

Report ($names -contains 'fabric.mod.json') 'fabric-metadata' 'fabric.mod.json'
Report ($names -contains 'META-INF/neoforge.mods.toml') 'neoforge-metadata' 'META-INF/neoforge.mods.toml'

$mixinConfigs = @($names | Where-Object { $_ -like '*.mixins.json' })
Report ($mixinConfigs.Count -eq 1) 'one-mixin-config' ($mixinConfigs -join ', ')

$selftest = Count-Under 'dropin/mc/selftest/'
Report ($selftest -eq 0) 'no-selftest-classes' ("$selftest entries under dropin/mc/selftest/")

# Bundled libraries: original package -> relocated package. All four must be relocated (DESIGN 5).
$bundled = [ordered]@{
    'dev/dirs/'                       = 'dropin/shadow/dirs/'
    'folk/sisby/kaleido/'             = 'dropin/shadow/kaleido/'
    'io/netty/incubator/codec/quic/'  = 'dropin/shadow/'
    'link/e4mc/iroh/'                 = 'dropin/shadow/'
}
foreach ($original in $bundled.Keys) {
    $left = Count-Under $original
    $detail = "$left classes left at $original"
    if ($left -eq 0) { $detail += (', {0} under {1}' -f (Count-Under $bundled[$original]), $bundled[$original]) }
    Report ($left -eq 0) ('relocated ' + $original.TrimEnd('/').Replace('/', '.')) $detail
}

if ($script:failures -gt 0) { exit 1 }
exit 0
