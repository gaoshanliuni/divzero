param([string]$OutputRoot = 'build/legacy189-toolchain')
$ErrorActionPreference = 'Stop'
$gradleVersion = '2.14.1'
$expected = 'cfc61eda71f2d12a572822644ce13d2919407595c2aec3e3566d2aab6f97ef39'
$root = [IO.Path]::GetFullPath((Join-Path (Get-Location) $OutputRoot))
New-Item -ItemType Directory -Path $root -Force | Out-Null
$archive = Join-Path $root "gradle-$gradleVersion-bin.zip"
if (-not (Test-Path -LiteralPath $archive)) {
    Invoke-WebRequest "https://services.gradle.org/distributions/gradle-$gradleVersion-bin.zip" -OutFile $archive -TimeoutSec 120
}
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) { throw 'LEGACY_GRADLE_HASH_MISMATCH' }
$gradle = Join-Path $root "gradle-$gradleVersion/bin/gradle.bat"
if (-not (Test-Path -LiteralPath $gradle)) { Expand-Archive -LiteralPath $archive -DestinationPath $root }
if (-not $env:LEGACY189_JAVA8) { throw 'LEGACY_JAVA8_REQUIRED' }
$env:JAVA_HOME = Split-Path (Split-Path $env:LEGACY189_JAVA8 -Parent) -Parent
$env:GRADLE_USER_HOME = Join-Path $root 'cache'
& $gradle -p legacy189/game setupCIWorkspace build --no-daemon --console=plain 2>&1 | Tee-Object -FilePath (Join-Path $root 'forge-build.log')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
