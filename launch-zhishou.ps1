$ErrorActionPreference = 'Stop'
$workspaceDir = Split-Path -Parent $PSScriptRoot
$javaLauncher = Join-Path $workspaceDir 'runtime/java/jdk-21.0.12.1+1/bin/javaw.exe'
$applicationJar = Join-Path $PSScriptRoot 'zhishou-executor.jar'
if (-not (Test-Path -LiteralPath $applicationJar -PathType Leaf)) {
    throw "未找到程序：$applicationJar；请先完成构建。"
}
if (-not (Test-Path -LiteralPath $javaLauncher -PathType Leaf)) {
    throw '未找到相邻 runtime 文件夹中的 Java。请把执手执行器、runtime、oculix-flow 文件夹放在同一个目录。'
}
$javaArguments = @(
    '-Dfile.encoding=UTF-8'
    '-Dstdout.encoding=UTF-8'
    '-Dstderr.encoding=UTF-8'
    '-Doculix.preferencesNode=org/zhishou/executor'
    ('"-Doculix.workspace=' + $workspaceDir + '"')
    '-jar'
    ('"' + $applicationJar + '"')
)
Start-Process -FilePath $javaLauncher -ArgumentList $javaArguments -WorkingDirectory $PSScriptRoot -WindowStyle Normal -RedirectStandardOutput (Join-Path $PSScriptRoot 'startup-output.log') -RedirectStandardError (Join-Path $PSScriptRoot 'startup-error.log')
