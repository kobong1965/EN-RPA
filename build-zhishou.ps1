param(
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '执手执行器')
)

$ErrorActionPreference = 'Stop'
$workspaceDir = $PSScriptRoot
$jdkDir = Join-Path $workspaceDir 'runtime/java/jdk-21.0.12.1+1'
$javaCompiler = Join-Path $jdkDir 'bin/javac.exe'
$jarTool = Join-Path $jdkDir 'bin/jar.exe'
$baseJar = Join-Path $workspaceDir 'OculiX/oculixide-4.0.0-windows.jar'
$rubyJar = Join-Path $workspaceDir 'runtime/downloads/jruby-complete-9.4.14.0.jar'
$utf8 = [System.Text.UTF8Encoding]::new($false)

foreach ($requiredFile in @($javaCompiler, $jarTool, $baseJar, $rubyJar)) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        throw "缺少构建文件：$requiredFile"
    }
}
$officialHash = '7eda19dff0e14ce462b535b788a7b48afda2ca163f33307579541706dab6cd0e'
$baseHash = (Get-FileHash -LiteralPath $baseJar -Algorithm SHA256).Hash.ToLowerInvariant()
if ($baseHash -ne $officialHash) { throw '官方依赖包校验失败；不会使用来源不明或已被改写的基础包。' }
$rubyHash = (Get-FileHash -LiteralPath $rubyJar -Algorithm SHA512).Hash.ToLowerInvariant()
if ($rubyHash -ne '5d76502d2587307eac9948a3fcc1fec4ccc17f3f11c9283851afa4c631d997ecbde0940c94db57f13e85fa0331bba786841b8d2c51e74f97189d644661ea79f3') {
    throw 'JRuby 编译依赖校验失败。'
}

New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$OutputDirectory = (Resolve-Path -LiteralPath $OutputDirectory).Path
$buildBase = Join-Path $PSScriptRoot '.local-build'
New-Item -ItemType Directory -Path $buildBase -Force | Out-Null
$stageDir = Join-Path $buildBase ('build-' + [DateTime]::Now.ToString('yyyyMMdd-HHmmss-fff'))
$classesDir = Join-Path $stageDir 'classes'
New-Item -ItemType Directory -Path $classesDir -Force | Out-Null
$sourceFiles = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'API/src/main/java'), (Join-Path $PSScriptRoot 'IDE/src/main/java') -Filter '*.java' -File -Recurse | Sort-Object FullName)
$sourceList = Join-Path $stageDir 'sources.txt'
$sourceArguments = $sourceFiles | ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' }
[System.IO.File]::WriteAllLines($sourceList, $sourceArguments, $utf8)

Write-Output "编译 API 与编辑器全部源码，共 $($sourceFiles.Count) 个文件。"
& $javaCompiler '-J-Dfile.encoding=UTF-8' '-J-Dstdout.encoding=UTF-8' '-J-Dstderr.encoding=UTF-8' '-encoding' 'UTF-8' '--release' '17' '-cp' ($baseJar + ';' + $rubyJar) '-d' $classesDir ('@' + $sourceList)
if ($LASTEXITCODE -ne 0) { throw "源码编译失败，退出码：$LASTEXITCODE；保留构建目录：$stageDir" }

# The official Windows dependency bundle is retained byte-for-byte as the base.
# Only this fork's freshly compiled API/IDE classes and source resources replace entries.
foreach ($resourceRoot in @((Join-Path $PSScriptRoot 'API/src/main/resources'), (Join-Path $PSScriptRoot 'IDE/src/main/resources'))) {
    foreach ($resource in Get-ChildItem -LiteralPath $resourceRoot -Force) {
        Copy-Item -LiteralPath $resource.FullName -Destination $classesDir -Recurse -Force
    }
}

# These are the Maven-filtered metadata fields from API/pom.xml and IDE/pom.xml.
$apiPom = [xml](Get-Content -LiteralPath (Join-Path $PSScriptRoot 'API/pom.xml') -Raw)
$idePom = [xml](Get-Content -LiteralPath (Join-Path $PSScriptRoot 'IDE/pom.xml') -Raw)
$versions = @{
    'project.version' = [string]$apiPom.project.version
    'timestamp' = [DateTime]::Now.ToString('yyyy-MM-dd_HH:mm')
    'versiontess4j' = [string]$apiPom.project.properties.versiontess4j
    'legerix.version' = [string]$apiPom.project.properties.'legerix.version'
    'apertix.opencv.version' = [string]$apiPom.project.properties.'apertix.opencv.version'
    'sikulixjythonversion' = [string]$idePom.project.properties.sikulixjythonversion
    'sikulixjrubyversion' = [string]$idePom.project.properties.sikulixjrubyversion
}
foreach ($metadata in Get-ChildItem -LiteralPath (Join-Path $classesDir 'Settings') -File) {
    $originalText = [System.IO.File]::ReadAllText($metadata.FullName, $utf8)
    $filteredText = $originalText
    foreach ($key in $versions.Keys) { $filteredText = $filteredText.Replace(('${' + $key + '}'), $versions[$key]) }
    if ($filteredText -ne $originalText) { [System.IO.File]::WriteAllText($metadata.FullName, $filteredText, $utf8) }
}

$pendingJar = Join-Path $stageDir 'zhishou-executor.jar'
Copy-Item -LiteralPath $baseJar -Destination $pendingJar
& $jarTool '--update' '--file' $pendingJar '-C' $classesDir '.'
if ($LASTEXITCODE -ne 0) { throw "打包失败，退出码：$LASTEXITCODE" }
$outputJar = Join-Path $OutputDirectory 'zhishou-executor.jar'
Move-Item -LiteralPath $pendingJar -Destination $outputJar -Force

$sourceCommit = (& git -C $PSScriptRoot rev-parse HEAD).Trim()
$buildInfo = [ordered]@{
    name = '执手执行器'
    upstream = 'https://github.com/oculix-org/Oculix'
    upstreamTag = 'v4.0.0'
    sourceCommit = $sourceCommit
    buildTime = [DateTime]::Now.ToString('o')
    sourceCount = $sourceFiles.Count
    compiler = 'JDK 21.0.12.1+1, javac --release 17'
    dependencyBase = 'Official oculixide-4.0.0-windows.jar'
    dependencyBaseSHA256 = $baseHash
    optionalCompileDependency = 'JRuby complete 9.4.14.0 (not added to the Windows runtime bundle)'
    outputSHA256 = (Get-FileHash -LiteralPath $outputJar -Algorithm SHA256).Hash.ToLowerInvariant()
    method = 'Compile all API/IDE Java sources; overlay classes and filtered resources onto the verified upstream Windows dependency bundle.'
    sourceRoot = $PSScriptRoot
    outputJar = $outputJar
}
[System.IO.File]::WriteAllText((Join-Path $OutputDirectory '构建信息.json'), ($buildInfo | ConvertTo-Json -Depth 5), $utf8)
foreach ($licenseName in @('LICENSE', 'LICENSE.txt', 'NOTICE', 'NOTICE.txt')) {
    $licenseSource = Join-Path $PSScriptRoot $licenseName
    if (Test-Path -LiteralPath $licenseSource -PathType Leaf) { Copy-Item -LiteralPath $licenseSource -Destination $OutputDirectory -Force }
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'launch-zhishou.ps1') -Destination (Join-Path $OutputDirectory '启动执手.ps1') -Force
foreach ($guideName in @('执手使用说明.md', '执行器接口.md')) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot ('docs/' + $guideName)) -Destination $OutputDirectory -Force
}
$shortcutShell = New-Object -ComObject WScript.Shell
$shortcut = $shortcutShell.CreateShortcut((Join-Path $OutputDirectory '启动执手.lnk'))
$shortcut.TargetPath = Join-Path $jdkDir 'bin/javaw.exe'
$shortcut.Arguments = '-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Doculix.preferencesNode=org/zhishou/executor "-Doculix.workspace=' + $workspaceDir + '" -jar "' + $outputJar + '"'
$shortcut.WorkingDirectory = $OutputDirectory
$shortcut.Description = '执手执行器：执行任务，查看结果'
$logoIcoSource = Join-Path $PSScriptRoot 'IDE/src/main/resources/icons/zhishou-logo.ico'
if (Test-Path -LiteralPath $logoIcoSource -PathType Leaf) {
    $logoIcoOutput = Join-Path $OutputDirectory 'zhishou-logo.ico'
    Copy-Item -LiteralPath $logoIcoSource -Destination $logoIcoOutput -Force
    $shortcut.IconLocation = $logoIcoOutput
}
$shortcut.WindowStyle = 1
$shortcut.Save()

Write-Output "构建完成：$outputJar"
Write-Output "SHA256：$($buildInfo.outputSHA256)"
Write-Output "完整源码及资源编译记录：$stageDir"
