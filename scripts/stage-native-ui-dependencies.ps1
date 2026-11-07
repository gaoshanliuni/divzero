param([Parameter(Mandatory)][string]$OutputDirectory)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$out=[IO.Path]::GetFullPath($OutputDirectory,$root)
if(-not $out.StartsWith((Join-Path $root 'build')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'DEPENDENCY_OUTPUT_BOUNDARY'}
New-Item -ItemType Directory -Path $out -Force | Out-Null
$build=[IO.File]::ReadAllText((Join-Path $root 'neoforge/build.gradle'))
$dependencies=@(
    @{file='ldlib2-neoforge-26.1-26.1.2.41.jar';project='B1CBVXHX';version='15aCZh6V';bytes=7341349;role='runtime';license='LGPL-3.0-only';source='https://github.com/Low-Drag-MC/LDLib2';sources='https://cdn.modrinth.com/data/B1CBVXHX/versions/15aCZh6V/ldlib2-neoforge-26.1-26.1.2.41-sources.jar'},
    @{file='kubejs-neoforge-26.1.2-8.0.6.jar';project='umyGl7zF';version='FzLyIIBB';bytes=2214228;role='runtime-ai-ui';license='LGPL-3.0-only';source='https://github.com/KubeJS-Mods/KubeJS';sources='https://maven.latvian.dev/releases/dev/latvian/mods/kubejs-neoforge/26.1.2-8.0.6/kubejs-neoforge-26.1.2-8.0.6-sources.jar'},
    @{file='better-advanced-tooltips-2601.1.0-build.9.jar';project='iHLDc0eo';version='dYc3PUcQ';bytes=38177;role='runtime-ai-ui-dependency';license='MIT';source='https://github.com/latvian-dev/better-advanced-tooltips';sources='https://github.com/latvian-dev/better-advanced-tooltips'}
)
$records=@()
foreach($dependency in $dependencies){
    if($dependency.project -in @('B1CBVXHX','umyGl7zF') -and (-not $build.Contains("nativeUiLocked('maven.modrinth:$($dependency.project):$($dependency.version)')"))){throw 'NATIVE_UI_RELEASE_LOCK_MISMATCH'}
    $target=Join-Path $out $dependency.file
    if(Test-Path -LiteralPath $target){throw 'NATIVE_UI_OUTPUT_ALREADY_EXISTS'}
    $url="https://cdn.modrinth.com/data/$($dependency.project)/versions/$($dependency.version)/$($dependency.file)"
    $temp=Join-Path $out ([Guid]::NewGuid().ToString()+'.download')
    try{
        for($attempt=1;$attempt -le 3;$attempt++){try{Invoke-WebRequest -Uri $url -OutFile $temp -TimeoutSec 180;break}catch{if($attempt -eq 3){throw};Start-Sleep -Seconds (2*$attempt)}}
        if((Get-Item -LiteralPath $temp).Length -ne $dependency.bytes){throw 'NATIVE_UI_DEPENDENCY_SIZE'}
        Move-Item -LiteralPath $temp -Destination $target
        $records += [ordered]@{file=$dependency.file;role=$dependency.role;project=$dependency.project;versionId=$dependency.version;upstream=$url;source=$dependency.source;correspondingSources=$dependency.sources;license=$dependency.license;bytes=$dependency.bytes}
    }finally{ # Incomplete downloads remain under the build directory for diagnostics.
    }
}
[IO.File]::WriteAllText((Join-Path $out 'DEPENDENCIES.json'),(ConvertTo-Json -InputObject @($records) -Depth 5),[Text.UTF8Encoding]::new($false))
Write-Output 'Verified LDLib2, optional AI-UI KubeJS and its tooltip dependency. Rhino is embedded in DivZero.'
