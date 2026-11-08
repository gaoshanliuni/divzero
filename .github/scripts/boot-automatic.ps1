param([Parameter(Mandatory)][string]$Candidate)
$ErrorActionPreference='Stop'
if ($env:CI -ne 'true' -or $env:GITHUB_ACTIONS -ne 'true') { throw 'BOOT_NATIVE_ACCEPTANCE_RUNS_ONLY_IN_ACTIONS' }
$candidatePath=(Resolve-Path -LiteralPath $Candidate).Path
$run=Join-Path (Get-Location) 'build/native-boot-ci'
$server=Join-Path $run 'server'
New-Item -ItemType Directory -Force $server | Out-Null
$installer=Join-Path $run 'neoforge-installer.jar'
Invoke-WebRequest 'https://maven.neoforged.net/releases/net/neoforged/neoforge/26.1.2.106/neoforge-26.1.2.106-installer.jar' -OutFile $installer
$java=Join-Path $env:JAVA_HOME 'bin/java.exe'
Push-Location $server
try {
    & $java -jar $installer --installServer 2>&1 | Set-Content (Join-Path $run 'installer.log')
    if ($LASTEXITCODE -ne 0) { throw 'BOOT_CI_SERVER_INSTALL_FAILED' }
    New-Item -ItemType Directory -Force 'mods' | Out-Null
    Copy-Item -LiteralPath $candidatePath -Destination 'mods/DivZero-native-ci.jar'
    $gradleRoot=if($env:GRADLE_USER_HOME){$env:GRADLE_USER_HOME}else{Join-Path $env:USERPROFILE '.gradle'}
    $ldlib=Get-ChildItem -LiteralPath (Join-Path $gradleRoot 'caches/modules-2/files-2.1/maven.modrinth/B1CBVXHX') -Recurse -File -Filter '*.jar' | Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1
    if (!$ldlib) { throw 'BOOT_CI_LDLIB_NOT_RESOLVED' }
    Copy-Item -LiteralPath $ldlib.FullName -Destination 'mods/ldlib2.jar'
    [IO.File]::WriteAllText((Join-Path $server 'eula.txt'),'eula=true')
    [IO.File]::WriteAllText((Join-Path $server 'server.properties'),"online-mode=false`nserver-ip=127.0.0.1`nserver-port=25575`nlevel-name=BootCiWorld`nlevel-type=minecraft:flat`nspawn-protection=0`nview-distance=2`nsimulation-distance=2`nmax-tick-time=120000`n")
    foreach($stage in @('prepare','restart')) {
        $argsFile=Join-Path $server ($stage+'-jvm.args')
        [IO.File]::WriteAllLines($argsFile,@('-Xmx5G','-XX:StackShadowPages=32','--enable-native-access=ALL-UNNAMED','--sun-misc-unsafe-memory-access=allow','-Dfile.encoding=UTF-8','-Dmineagent.bootAutomaticCiSmoke=true',"-Dmineagent.bootAutomaticCiStage=$stage"))
        $process=Start-Process -FilePath $java -ArgumentList "@$stage-jvm.args @libraries/net/neoforged/neoforge/26.1.2.106/win_args.txt nogui" -WorkingDirectory $server -WindowStyle Hidden -RedirectStandardOutput (Join-Path $run ($stage+'-stdout.log')) -RedirectStandardError (Join-Path $run ($stage+'-stderr.log')) -PassThru
        $deadline=(Get-Date).AddMinutes(12)
        while(!$process.HasExited -and (Get-Date) -lt $deadline) { Start-Sleep -Seconds 1; $process.Refresh() }
        if(!$process.HasExited) { $process.Kill($true); throw "BOOT_CI_PROCESS_TIMEOUT_$stage" }
        $receipt=Join-Path $server "boot-automatic-ci/$stage.json"
        if(!(Test-Path -LiteralPath $receipt)) {
            $failure=Join-Path $server "boot-automatic-ci/$stage-failure.json"
            if(Test-Path -LiteralPath $failure) { Get-Content -LiteralPath $failure }
            Get-Content -LiteralPath (Join-Path $run ($stage+'-stdout.log')) -Tail 35
            throw "BOOT_CI_RECEIPT_MISSING_$stage"
        }
        $result=Get-Content -LiteralPath $receipt -Raw | ConvertFrom-Json
        if($result.status -ne 'PASS') { throw "BOOT_CI_FAILED_$stage" }
        Copy-Item -LiteralPath $receipt -Destination (Join-Path $run ($stage+'.json'))
        Get-Content -LiteralPath $receipt
    }
} finally { Pop-Location }
