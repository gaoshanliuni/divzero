param([string]$OutputDirectory = 'build/ci-artifacts')
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$out=[IO.Path]::GetFullPath($OutputDirectory,$root)
if (-not $out.StartsWith((Join-Path $root 'build')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'OFFLINE_MCEF_OUTPUT_BOUNDARY' }
$lock=Get-Content -LiteralPath (Join-Path $root 'gradle/mcef-offline.lock.json') -Raw | ConvertFrom-Json
if ($lock.repository -ne 'gaoshanliuni/MCEF-Offline' -or $lock.tag -notmatch '^offline-2\.2\.0-[a-f0-9]{12}-r[0-9]+-a[0-9]+$' -or $lock.sourceCommit -notmatch '^[a-f0-9]{40}$') { throw 'OFFLINE_MCEF_LOCK_INVALID' }
$items=@($lock.sources)+@($lock.notices)+@($lock.manifest)+@($lock.platforms.PSObject.Properties | ForEach-Object Value)
$records=@()
foreach($item in $items) {
    if ($item.file -notmatch '^[A-Za-z0-9][A-Za-z0-9_.-]*$' -or $item.sha256 -notmatch '^[a-f0-9]{64}$') { throw 'OFFLINE_MCEF_ARTIFACT_IDENTITY_INVALID' }
    $destination=Join-Path $out $item.file
    if(Test-Path -LiteralPath $destination) { throw 'OFFLINE_MCEF_OUTPUT_ALREADY_EXISTS' }
    $temp=Join-Path $out ([Guid]::NewGuid().ToString()+'.download')
    $url="https://github.com/$($lock.repository)/releases/download/$($lock.tag)/$($item.file)"
    try {
        for($attempt=1;$attempt -le 3;$attempt++) {
            try { Invoke-WebRequest -Uri $url -OutFile $temp -TimeoutSec 600 -Headers @{'User-Agent'='DivZero pinned offline MCEF staging'}; break }
            catch { if($attempt -eq 3) { throw }; Start-Sleep -Seconds (2*$attempt) }
        }
        $actual=(Get-FileHash -LiteralPath $temp -Algorithm SHA256).Hash.ToLowerInvariant()
        if($actual -ne $item.sha256 -or (Get-Item -LiteralPath $temp).Length -ne [long]$item.size) { throw "OFFLINE_MCEF_HASH_MISMATCH: $($item.file)" }
        Move-Item -LiteralPath $temp -Destination $destination
    } finally { if(Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp } }
    $role=if($item.file -like 'mcef-offline-neoforge-*.jar') {'offline-runtime-alternative-select-one'} elseif($item.file -like '*sources.jar') {'sources-not-for-mods'} else {'offline-metadata'}
    $records += [ordered]@{file=$item.file;role=$role;source="https://github.com/$($lock.repository)/commit/$($lock.sourceCommit)";upstream=$url;versionId=$lock.tag;license='LGPL-2.1-or-later';sha256=$item.sha256}
}
$manifest=Get-Content -LiteralPath (Join-Path $out $lock.manifest.file) -Raw | ConvertFrom-Json
if($manifest.sourceCommit -ne $lock.sourceCommit -or $manifest.tag -ne $lock.tag -or $manifest.jcefCommit -ne $lock.jcefCommit) { throw 'OFFLINE_MCEF_RELEASE_IDENTITY_MISMATCH' }
foreach($property in $lock.platforms.PSObject.Properties) {
    $platform=$property.Name;$item=$property.Value
    if($manifest.platforms.$platform.sha256 -ne $item.sha256) { throw 'OFFLINE_MCEF_PLATFORM_IDENTITY_MISMATCH' }
    $jar=[IO.Compression.ZipFile]::OpenRead((Join-Path $out $item.file))
    try {
        foreach($entry in @('com/cinemamod/mcef/offline/OfflineRuntime.class',"mcef-offline/$platform/runtime.zip","mcef-offline/$platform/runtime.properties")) {
            if($null -eq $jar.GetEntry($entry)) { throw "OFFLINE_MCEF_MISSING_EMBEDDED_RUNTIME: $platform" }
        }
    } finally { $jar.Dispose() }
}
$dependencies=@(Get-Content -LiteralPath (Join-Path $out 'DEPENDENCIES.json') -Raw | ConvertFrom-Json)+$records
[IO.File]::WriteAllText((Join-Path $out 'DEPENDENCIES.json'),(ConvertTo-Json -InputObject @($dependencies) -Depth 8),[Text.UTF8Encoding]::new($false))
$info=Get-Content -LiteralPath (Join-Path $out 'BUILD-INFO.json') -Raw | ConvertFrom-Json
$info.dependencies=$dependencies
$info | Add-Member -NotePropertyName offlineMcef -NotePropertyValue $lock -Force
[IO.File]::WriteAllText((Join-Path $out 'BUILD-INFO.json'),($info|ConvertTo-Json -Depth 12),[Text.UTF8Encoding]::new($false))
$readme=@"
DivZero: install the main Mod JAR and WebGUI, then choose ONE MCEF option.
Recommended offline option: mcef-offline-neoforge-<platform>.jar matching your OS and Java architecture.
Platforms: windows_amd64, linux_amd64, macos_amd64, macos_arm64.
Native Windows/Linux ARM64 packages are not provided because the pinned upstream bundles contain x64 binaries.
Alternative online option: mcef_neoforge_2.2.0_MC_26.1.1.jar (downloads its runtime at startup).
NEVER install both original and offline MCEF, or multiple platform MCEF JARs.
Do not install API/source/JSON/license files into mods.
Offline MCEF is pinned to $($lock.tag), source $($lock.sourceCommit).
Bundled runtime is installed and repaired locally; online web pages and AI APIs still need network access.
These are CI development builds. Compilation, packaging and installer tests do not replace game/browser acceptance.
See BUILD-INFO.json for Minecraft/NeoForge/Java versions and SHA256SUMS for every attachment.
"@
[IO.File]::WriteAllText((Join-Path $out 'README.txt'),$readme,[Text.UTF8Encoding]::new($false))
$checksums=foreach($file in Get-ChildItem -LiteralPath $out -File | Where-Object Name -ne 'SHA256SUMS' | Sort-Object Name) {
    '{0}  {1}' -f (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant(),$file.Name
}
[IO.File]::WriteAllText((Join-Path $out 'SHA256SUMS'),(($checksums -join "`n")+"`n"),[Text.UTF8Encoding]::new($false))
Write-Output "OFFLINE_MCEF_STAGED=$($lock.tag) PLATFORMS=$(@($lock.platforms.PSObject.Properties).Count)"
