$ErrorActionPreference = 'Stop'
$java = Get-Command java.exe -ErrorAction Stop
$jar = Join-Path $PSScriptRoot 'dist/zhishou-executor.jar'
if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw '缺少程序文件，请先运行 git lfs pull。' }
Start-Process -FilePath $java.Source -ArgumentList @(
  '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
  '-Doculix.preferencesNode=org/zhishou/executor',
  ('"-Doculix.workspace=' + $PSScriptRoot + '"'), '-jar', ('"' + $jar + '"')
) -WorkingDirectory $PSScriptRoot -WindowStyle Normal
