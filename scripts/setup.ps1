# Scarica whisper.cpp alla versione provata (una volta sola).
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
$dest = "app\src\main\cpp\whisper.cpp"
if (Test-Path "$dest\CMakeLists.txt") { Write-Host "whisper.cpp gia' presente"; exit 0 }
git clone --depth 1 --branch v1.7.6 https://github.com/ggml-org/whisper.cpp $dest
Write-Host "Fatto."
