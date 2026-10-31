param([ValidateSet('probe','baseline','moved-small','maximized','restore','ocr')][string]$Mode='baseline')
$ErrorActionPreference='Stop'
$javaPath=Join-Path $PSScriptRoot '../runtime/java/jdk-21.0.12.1+1/bin/java.exe'
$jarPath=Join-Path $PSScriptRoot '../runtime/oculixapi-4.0.0-windows.jar'
Push-Location -LiteralPath $PSScriptRoot
try { & $javaPath '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' '-cp' $jarPath 'SycmFlow.java' $Mode; if($LASTEXITCODE -ne 0){ throw "OculiX stopped with exit code $LASTEXITCODE. See runs/ for screenshots and logs." } }
finally { Pop-Location }
