<#
.SYNOPSIS
  Gate jar check (docs/MILESTONES.md, common gate), 1.12.2 (Cleanroom) port: exactly one mod jar; it contains
  mcmod.info, the @Mod class, one mixin config (manifest MixinConfigs) with its refmap, and the .lang files;
  bundled libraries are relocated; netty-quic is nested; no self-test class is in the jar. Prints one line per item and exits non-zero if any item fails.
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

# 1.12.2 port (Cleanroom): one loader. The jar is MCo-op-<version>+mc1.12.2.jar, Forge reads mcmod.info and the
# @Mod class, and Cleanroom takes the one mixin config from the manifest's MixinConfigs (CleanroomModDiscoverer).
function Read-Entry([string]$entry) {
    $z = [System.IO.Compression.ZipFile]::OpenRead($jars[0].FullName)
    try {
        $e = $z.GetEntry($entry)
        if ($null -eq $e) { return '' }
        $r = New-Object System.IO.StreamReader($e.Open())
        try { return $r.ReadToEnd() } finally { $r.Dispose() }
    } finally { $z.Dispose() }
}
Report ($jars[0].Name -match '^MCo-op-.+\+mc1\.12\.2\.jar$') 'jar-name' $jars[0].Name
$modInfo = Read-Entry 'mcmod.info'
Report ($modInfo -match '"modid"\s*:\s*"dropin"' -and $modInfo -notmatch '\$\{') 'forge-metadata' 'mcmod.info (modid dropin, expanded)'
Report ($names -contains 'dropin/cleanroom/DropInCleanroom.class') 'mod-class' 'dropin/cleanroom/DropInCleanroom.class'
$manifest = Read-Entry 'META-INF/MANIFEST.MF'
$declared = @([regex]::Matches($manifest, 'MixinConfigs:\s*(\S+)') | ForEach-Object { $_.Groups[1].Value -split ',' })
Report ($declared.Count -eq 1 -and ($names -contains $declared[0])) 'one-mixin-config' ('manifest MixinConfigs: ' + ($declared -join ', '))
$mixinConfigs = @($names | Where-Object { $_ -like '*.mixins.json' })
$extra = @($mixinConfigs | Where-Object { $declared -notcontains $_ })
Report ($extra.Count -eq 0) 'no-other-mixin-config' ('in jar: ' + ($mixinConfigs -join ', '))
$config = if ($declared.Count -eq 1) { Read-Entry $declared[0] } else { '' }
$refmap = [regex]::Match($config, '"refmap"\s*:\s*"([^"]+)"').Groups[1].Value
Report ($refmap -ne '' -and ($names -contains $refmap)) 'refmap' ("refmap '$refmap' named by the config and in the jar")
Report ($names -contains 'assets/dropin_coop/lang/en_us.lang' -and $names -contains 'assets/dropin/lang/en_us.lang') 'legacy-lang' 'assets/*/lang/en_us.lang'

foreach ($prefix in @('dropin/mc/selftest/')) {
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

# JNI-bound libraries: their native libraries bind to these class names, so they keep their original packages
# (DESIGN 5). 1.12.2 port: Cleanroom hands every io.netty.* class to the JDK's app class loader, so netty-quic ships
# as a nested jar (dropin/libs/netty-quic.jar) that QuicBootstrap appends there; its classes must not be in the jar.
$present = Count-Under 'link/e4mc/iroh/'
$moved = Count-Under 'dropin/shadow/link/e4mc/iroh/'
Report ($present -gt 0 -and $moved -eq 0) 'unrelocated link.e4mc.iroh' ('{0} classes at link/e4mc/iroh/, {1} relocated copies' -f $present, $moved)
$quicLoose = (Count-Under 'io/netty/incubator/codec/quic/') + (Count-Under 'dropin/shadow/io/netty/')
Report ($names -contains 'dropin/libs/netty-quic.jar' -and $quicLoose -eq 0) 'nested netty-quic' ("dropin/libs/netty-quic.jar; $quicLoose loose quic classes")

if ($script:failures -gt 0) { exit 1 }
exit 0
