# Build script: compile, test, then package.
#   powershell -ExecutionPolicy Bypass -File build.ps1
#
# Output in dist\:
#   ezmj-<version>.jar                       runnable jar (needs Java 17+)
#   EZMJ-<version>-windows-portable\         portable app, run EZMJ.exe (bundles its own Java)
#   EZMJ-<version>-windows-portable.zip      the same folder, zipped
#   EZMJ-windows-portable.zip                the same zip again without the version, so its name never changes
#   EZMJ-<version>-setup.exe                 installer, only when WiX 3 is in tools\wix314 or on PATH
#
# The version comes from src\ezmj\version.properties.
# Stops before touching anything unless CHANGELOG.md has a section for that version
# and dist\ does not already hold it. See "打包流程" in README.md.

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

function Invoke-Tool {
    param([string]$Tool, [string[]]$Arguments)
    $ErrorActionPreference = 'Continue'
    & $Tool @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Tool failed with exit code $LASTEXITCODE"
    }
}

$version = (Select-String -Path 'src\ezmj\version.properties' -Pattern '^version=(.+)$').Matches[0].Groups[1].Value.Trim()
$copyright = 'Copyright (C) 2026 snick911'
Write-Host "== EZMJ $version =="

if (-not (Select-String -Path 'CHANGELOG.md' -Pattern "^## $([regex]::Escape($version))( |$)" -Quiet)) {
    throw "CHANGELOG.md has no '## $version' section. Record the changes before packaging."
}
if (Test-Path "dist\ezmj-$version.jar") {
    throw "$version is already packaged in dist\. Bump src\ezmj\version.properties, or delete dist\ to rebuild the same version."
}

foreach ($dir in 'build', 'dist') {
    if (Test-Path $dir) {
        Remove-Item -Recurse -Force -Confirm:$false $dir
    }
}
$classes = 'build\classes'
$testClasses = 'build\test-classes'
New-Item -ItemType Directory -Force $classes, $testClasses, 'dist' | Out-Null

Write-Host '== Compile =='
$sources = Get-ChildItem -Recurse src -Filter *.java | ForEach-Object { $_.FullName }
Invoke-Tool javac (@('--release', '17', '-encoding', 'UTF-8', '-d', $classes) + $sources)
Get-ChildItem -Recurse src -File | Where-Object { $_.Extension -ne '.java' } | ForEach-Object {
    $relative = $_.FullName.Substring((Resolve-Path src).Path.Length + 1)
    $target = Join-Path $classes $relative
    New-Item -ItemType Directory -Force (Split-Path $target) | Out-Null
    Copy-Item $_.FullName $target
}

Write-Host '== Test =='
$testSources = Get-ChildItem -Recurse test -Filter *.java | ForEach-Object { $_.FullName }
Invoke-Tool javac (@('--release', '17', '-encoding', 'UTF-8', '-cp', $classes, '-d', $testClasses) + $testSources)
Invoke-Tool java @('-Dfile.encoding=UTF-8', '-cp', "$classes;$testClasses", 'ezmj.AllTests')

Write-Host '== Jar =='
$jarName = "ezmj-$version.jar"
$manifest = 'build\MANIFEST.MF'
"Implementation-Title: EZMJ`r`nImplementation-Version: $version`r`n" | Set-Content -Encoding Ascii $manifest
New-Item -ItemType Directory -Force "$classes\META-INF" | Out-Null
Copy-Item 'LICENSE' "$classes\META-INF\LICENSE"
Invoke-Tool jar @('--create', '--file', "dist\$jarName", '--manifest', $manifest, '--main-class', 'ezmj.Main', '-C', $classes, '.')

Write-Host '== Windows app =='
$input_ = 'build\jpackage-input'
New-Item -ItemType Directory -Force $input_ | Out-Null
Copy-Item "dist\$jarName" $input_
Invoke-Tool jpackage @(
    '--type', 'app-image',
    '--name', 'EZMJ',
    '--app-version', $version,
    '--vendor', 'EZMJ',
    '--copyright', $copyright,
    '--description', 'Taiwan 16-tile Mahjong',
    '--input', $input_,
    '--main-jar', $jarName,
    '--main-class', 'ezmj.Main',
    '--icon', 'packaging\ezmj.ico',
    '--add-modules', 'java.desktop,java.net.http,jdk.crypto.ec',
    '--jlink-options', '--strip-native-commands --strip-debug --no-man-pages --no-header-files --compress=2',
    '--dest', 'build\image'
)
# The portable app and the installer are both made from this folder, so the license sits next to EZMJ.exe.
Copy-Item 'LICENSE' 'build\image\EZMJ\LICENSE.txt'

$portableName = "EZMJ-$version-windows-portable"
Copy-Item -Recurse 'build\image\EZMJ' "dist\$portableName"
Compress-Archive -Path "dist\$portableName" -DestinationPath "dist\$portableName.zip"
# The same zip without the version in its name, so a link to the latest portable app never changes.
Copy-Item "dist\$portableName.zip" 'dist\EZMJ-windows-portable.zip'

$localWix = Join-Path $PSScriptRoot 'tools\wix314'
if (Test-Path (Join-Path $localWix 'candle.exe')) {
    $env:PATH = "$localWix;$env:PATH"
}
if ((Get-Command candle -ErrorAction SilentlyContinue) -and (Get-Command light -ErrorAction SilentlyContinue)) {
    Write-Host '== Windows installer =='
    Invoke-Tool jpackage @(
        '--type', 'exe',
        '--name', 'EZMJ',
        '--app-version', $version,
        '--vendor', 'EZMJ',
        '--copyright', $copyright,
        '--description', 'Taiwan 16-tile Mahjong',
        '--app-image', 'build\image\EZMJ',
        '--win-menu',
        '--win-menu-group', 'EZMJ',
        '--win-shortcut',
        '--win-dir-chooser',
        '--win-per-user-install',
        '--win-upgrade-uuid', '7c1d3f52-6a0e-4c8b-9e21-5b4f0a7d2e16',
        '--dest', 'dist'
    )
    Rename-Item "dist\EZMJ-$version.exe" "EZMJ-$version-setup.exe"
} else {
    Write-Warning 'WiX 3 (candle/light) was not found in tools\wix314 or on PATH, skipping the installer.'
}

Write-Host '== Done =='
Get-ChildItem dist | Select-Object Name, Length | Format-Table -AutoSize | Out-String | Write-Host
