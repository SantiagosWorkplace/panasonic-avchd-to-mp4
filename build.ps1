# Compila HandycamConverter y arma dist\HANDYCAM\HANDYCAM.exe (con Java y ffmpeg incluidos).
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$jdk = Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory | Where-Object Name -like 'jdk-21*' | Select-Object -First 1
if (-not $jdk) { throw 'No se encontró el JDK 21 de Temurin.' }
$bin = Join-Path $jdk.FullName 'bin'

$ffmpeg = (Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Recurse -Filter ffmpeg.exe -ErrorAction SilentlyContinue | Select-Object -First 1).FullName
if (-not $ffmpeg) { throw 'No se encontró ffmpeg.exe.' }

# Prefijo \\?\ para poder borrar rutas de más de 260 caracteres.
foreach ($d in 'build', 'dist') {
    if (Test-Path $d) { cmd /c rmdir /s /q "\\?\$PSScriptRoot\$d" }
    if (Test-Path $d) { throw "No se pudo borrar $d (¿está abierto HANDYCAM.exe?)" }
}
New-Item -ItemType Directory build\classes, build\jar | Out-Null

& "$bin\javac.exe" -encoding UTF-8 --release 21 -d build\classes src\HandycamConverter.java
if ($LASTEXITCODE) { throw 'Falló javac' }
& "$bin\jar.exe" --create --file build\jar\HandycamConverter.jar --main-class HandycamConverter -C build\classes .
if ($LASTEXITCODE) { throw 'Falló jar' }

& "$bin\jpackage.exe" --type app-image --name HANDYCAM --input build\jar `
    --main-jar HandycamConverter.jar --main-class HandycamConverter --dest dist `
    --add-modules java.base,java.desktop
if ($LASTEXITCODE) { throw 'Falló jpackage' }

Copy-Item $ffmpeg dist\HANDYCAM\ffmpeg.exe
Write-Host "Listo: $PSScriptRoot\dist\HANDYCAM\HANDYCAM.exe"
