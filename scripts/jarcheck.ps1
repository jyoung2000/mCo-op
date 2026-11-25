<#
.SYNOPSIS
  Gate jar check (docs/MILESTONES.md, common gate): exactly one mod jar; it contains fabric.mod.json,
  META-INF/mods.toml (Forge) and one mixin config per loader; bundled libraries are relocated; no self-test class
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
Report ($names -contains 'META-INF/mods.toml') 'forge-metadata' 'META-INF/mods.toml'

# Obfuscated Minecraft versions (this port): Fabric runs intermediary names and Forge SRG names, so each
# loader has its own copy of the Minecraft-facing classes and exactly one mixin config of its own (DESIGN 5 asks
# for one config on 26.x, where both loaders share the classes). Checked: each loader's metadata names exactly
# one config (Forge 1.20.1 names it in the manifest's MixinConfigs attribute), that config is in the jar, and the jar holds no other mixin config.
function Read-Entry([string]$entry) {
    $z = [System.IO.Compression.ZipFile]::OpenRead($jars[0].FullName)
    try {
        $e = $z.GetEntry($entry)
        if ($null -eq $e) { return '' }
        $r = New-Object System.IO.StreamReader($e.Open())
        try { return $r.ReadToEnd() } finally { $r.Dispose() }
    } finally { $z.Dispose() }
}
$fabricMeta = Read-Entry 'fabric.mod.json'
$neoMeta = Read-Entry 'META-INF/MANIFEST.MF'
$fabricConfigs = @([regex]::Matches($fabricMeta, '"([^"]+\.mixins\.json)"') | ForEach-Object { $_.Groups[1].Value })
$neoConfigs = @([regex]::Matches($neoMeta, 'MixinConfigs:\s*([^\s,]+)') | ForEach-Object { $_.Groups[1].Value })
$mixinConfigs = @($names | Where-Object { $_ -like '*.mixins.json' })
Report ($fabricConfigs.Count -eq 1 -and ($names -contains $fabricConfigs[0])) 'one-mixin-config fabric' ($fabricConfigs -join ', ')
Report ($neoConfigs.Count -eq 1 -and ($names -contains $neoConfigs[0])) 'one-mixin-config forge' ($neoConfigs -join ', ')
$expected = @($fabricConfigs + $neoConfigs | Sort-Object -Unique)
$extra = @($mixinConfigs | Where-Object { $expected -notcontains $_ })
Report ($extra.Count -eq 0) 'no-other-mixin-config' ('in jar: ' + ($mixinConfigs -join ', '))

foreach ($prefix in @('dropin/mc/selftest/', 'dropin/fg/mc/selftest/')) {
    $selftest = Count-Under $prefix
    Report ($selftest -eq 0) 'no-selftest-classes' ("$selftest entries under $prefix")
}

# Bundled libraries that must be relocated: original package -> relocated package (DESIGN 5).
$bundled = [ordered]@{
    'dev/dirs/'           = 'dropin/shadow/dirs/'
    'folk/sisby/kaleido/' = 'dropin/shadow/kaleido/'
}
foreach ($original in $bundled.Keys) {
    $left = Count-Under $original
    $moved = Count-Under $bundled[$original]
    $detail = '{0} classes left at {1}, {2} under {3}' -f $left, $original, $moved, $bundled[$original]
    Report ($left -eq 0 -and $moved -gt 0) ('relocated ' + $original.TrimEnd('/').Replace('/', '.')) $detail
}

# JNI-bound libraries: their native libraries bind to these class names, so they must be shaded
# at their original packages and must not also appear relocated (DESIGN 5).
foreach ($original in @('io/netty/incubator/codec/quic/', 'link/e4mc/iroh/')) {
    $present = Count-Under $original
    $moved = Count-Under ('dropin/shadow/' + $original)
    $detail = '{0} classes at {1}, {2} relocated copies' -f $present, $original, $moved
    Report ($present -gt 0 -and $moved -eq 0) ('unrelocated ' + $original.TrimEnd('/').Replace('/', '.')) $detail
}

if ($script:failures -gt 0) { exit 1 }
exit 0
