param([Parameter(Mandatory)][string]$OutputDirectory)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$out=[IO.Path]::GetFullPath($OutputDirectory,$root)
if(-not $out.StartsWith((Join-Path $root 'build')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'DEPENDENCY_OUTPUT_BOUNDARY'}
New-Item -ItemType Directory -Path $out -Force | Out-Null
$build=[IO.File]::ReadAllText((Join-Path $root 'neoforge/build.gradle'))
$dependencies=@(
    @{file='ldlib2-neoforge-26.1-26.1.2.41.jar';project='B1CBVXHX';version='15aCZh6V';sha256='613f7e5d890c3c6c89f62e52a6336e775d83766c39cd52d5e1822e45c3520826';role='runtime';license='LGPL-3.0-only';source='https://github.com/Low-Drag-MC/LDLib2';sources='https://cdn.modrinth.com/data/B1CBVXHX/versions/15aCZh6V/ldlib2-neoforge-26.1-26.1.2.41-sources.jar'},
    @{file='kubejs-neoforge-26.1.2-8.0.6.jar';project='umyGl7zF';version='FzLyIIBB';sha256='36df262649e53c4b8256193198e7f5d225a6fec05f4b1e74e55c271e657ea5f0';role='runtime-ai-ui';license='LGPL-3.0-only';source='https://github.com/KubeJS-Mods/KubeJS';sources='https://maven.latvian.dev/releases/dev/latvian/mods/kubejs-neoforge/26.1.2-8.0.6/kubejs-neoforge-26.1.2-8.0.6-sources.jar'},
    @{file='better-advanced-tooltips-2601.1.0-build.9.jar';project='iHLDc0eo';version='dYc3PUcQ';sha256='1aef8ecc6f1b6c1952fed84c0302101019b2eb43c27d254152913b05a675143f';role='runtime-ai-ui-dependency';license='MIT';source='https://github.com/latvian-dev/better-advanced-tooltips';sources='https://github.com/latvian-dev/better-advanced-tooltips'}
)
$records=@()
foreach($dependency in $dependencies){
    if($dependency.project -in @('B1CBVXHX','umyGl7zF') -and (-not $build.Contains("nativeUiLocked('maven.modrinth:$($dependency.project):$($dependency.version)')") -or -not $build.Contains($dependency.sha256))){throw 'NATIVE_UI_RELEASE_LOCK_MISMATCH'}
    $target=Join-Path $out $dependency.file
    if(Test-Path -LiteralPath $target){throw 'NATIVE_UI_OUTPUT_ALREADY_EXISTS'}
    $url="https://cdn.modrinth.com/data/$($dependency.project)/versions/$($dependency.version)/$($dependency.file)"
    $temp=Join-Path $out ([Guid]::NewGuid().ToString()+'.download')
    try{
        for($attempt=1;$attempt -le 3;$attempt++){try{Invoke-WebRequest -Uri $url -OutFile $temp -TimeoutSec 180;break}catch{if($attempt -eq 3){throw};Start-Sleep -Seconds (2*$attempt)}}
        if((Get-FileHash -LiteralPath $temp -Algorithm SHA256).Hash.ToLowerInvariant() -ne $dependency.sha256){throw 'NATIVE_UI_DEPENDENCY_HASH'}
        Move-Item -LiteralPath $temp -Destination $target
        $records += [ordered]@{file=$dependency.file;role=$dependency.role;project=$dependency.project;versionId=$dependency.version;upstream=$url;source=$dependency.source;correspondingSources=$dependency.sources;license=$dependency.license;sha256=$dependency.sha256}
    }finally{if(Test-Path -LiteralPath $temp){Remove-Item -LiteralPath $temp}}
}
[IO.File]::WriteAllText((Join-Path $out 'DEPENDENCIES.json'),(ConvertTo-Json -InputObject @($records) -Depth 5),[Text.UTF8Encoding]::new($false))
Write-Output 'Verified LDLib2, optional AI-UI KubeJS and its tooltip dependency. Rhino is embedded in DivZero.'
