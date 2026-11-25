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
# Mixins must survive reobfuscation. CleanroomGradle's reobfJar renames only through the class hierarchy and passes the
# mixin processor no SRG output, and Cleanroom's run-time remapper chain (CleanMixService) only maps notch -> SRG, so a
# remapped @Shadow/@Overwrite member, or a mixin calling one of its own members by an MCP name, keeps a name that the
# SRG-named game does not have. Members of the target are reached by casting to it (renamed by reobfJar) or through
# @Accessor/@Invoker (refmap). This fails on any remapped @Shadow/@Overwrite and on any MCP name a mixin class
# references on itself.
$mcp2srg = Join-Path $root 'cleanroom\build\cleanroom_gradle\userdev\mcp2srg.tsrg'
$javap = if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\javap.exe'))) { Join-Path $env:JAVA_HOME 'bin\javap.exe' } else { 'javap' }
if (-not (Test-Path $mcp2srg)) {
    Report $false 'mixin-reobf' "missing $mcp2srg (run the build first)"
} else {
    $mcpNames = New-Object 'System.Collections.Generic.HashSet[string]'
    foreach ($line in [System.IO.File]::ReadLines($mcp2srg)) {
        if (-not $line.StartsWith("`t")) { continue }
        $p = $line.Trim() -split '\s+'
        if ($p.Count -eq 3 -and $p[0] -ne $p[2]) { [void]$mcpNames.Add($p[0]) }
        elseif ($p.Count -eq 2 -and $p[0] -ne $p[1]) { [void]$mcpNames.Add($p[0]) }
    }
    $tmp = Join-Path ([System.IO.Path]::GetTempPath()) ('dropin-jarcheck-' + [guid]::NewGuid())
    $mixinClasses = @($names | Where-Object { $_.StartsWith('dropin/mixin/') -and $_.EndsWith('.class') })
    $z = [System.IO.Compression.ZipFile]::OpenRead($jars[0].FullName)
    try {
        foreach ($entry in $mixinClasses) {
            $target = Join-Path $tmp $entry
            New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($z.GetEntry($entry), $target)
        }
    } finally { $z.Dispose() }
    $problems = @()
    try {
        foreach ($entry in $mixinClasses) {
            $internal = $entry.Substring(0, $entry.Length - 6)
            $lines = @(& $javap -v -p -c -cp $tmp ($internal.Replace('/', '.')))
            $member = ''
            for ($i = 0; $i -lt $lines.Count; $i++) {
                $l = $lines[$i]
                if ($l -match '^  \S.*[;)]$' -or $l -match '^  \S.*\) throws .*;$') { $member = $l.Trim() }
                if ($l -match '^\s+org\.spongepowered\.asm\.mixin\.(Shadow|Overwrite)(\(|$)') {
                    $kind = $Matches[1]
                    $annotationArgs = ''
                    for ($j = $i + 1; $j -lt $lines.Count -and $lines[$j] -notmatch '^\s+\d+: #' -and $lines[$j] -notmatch '^\s*$' -and $lines[$j] -notmatch '^  \S'; $j++) { $annotationArgs += $lines[$j] }
                    if ($annotationArgs -notmatch 'remap=false') { $problems += "$internal @$kind $member" }
                }
                foreach ($m in [regex]::Matches($l, '// (?:Field|Method|InterfaceMethod) (?:([\w$/]+)\.)?([\w$]+):')) {
                    $owner = $m.Groups[1].Value
                    if (($owner -eq '' -or $owner -eq $internal) -and $mcpNames.Contains($m.Groups[2].Value)) {
                        $problems += "$internal references its own MCP-named member $($m.Groups[2].Value)"
                    }
                }
            }
        }
    } finally { Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue }
    $problems = @($problems | Select-Object -Unique)
    Report ($problems.Count -eq 0) 'mixin-reobf' ("{0} mixin classes; {1}" -f $mixinClasses.Count, $(if ($problems.Count -eq 0) { 'no remapped @Shadow/@Overwrite, no MCP self-references' } else { $problems -join '; ' }))
}

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
