param([switch]$Uninstall)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$script = Join-Path $PSScriptRoot 'daily_scheduler.py'
$bundledPython = Join-Path $PSScriptRoot 'ocr/.venv/Scripts/python.exe'
$python = if (Test-Path -LiteralPath $bundledPython) { (Resolve-Path -LiteralPath $bundledPython).Path } else { (Get-Command python -ErrorAction Stop).Source }
$taskName = '执手-每日跨平台数据-北京时间0600'
if ($Uninstall) {
  Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue
  $startupShortcut = Join-Path ([Environment]::GetFolderPath('Startup')) '执手 · 每日数据调度.lnk'
  Remove-Item -LiteralPath $startupShortcut -Force -ErrorAction SilentlyContinue
  Write-Output "removed $taskName"
  exit 0
}
$action = New-ScheduledTaskAction -Execute $python -Argument "-B `"$script`" --daemon" -WorkingDirectory $PSScriptRoot
$trigger = New-ScheduledTaskTrigger -AtLogOn
$settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit (New-TimeSpan -Days 3650) -MultipleInstances IgnoreNew
$installedBy = ''
try {
  Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Settings $settings -Description '本机常驻调度：每天北京时间06:00运行 daily_workflow' -Force -ErrorAction Stop | Out-Null
  $installedBy = 'Windows任务计划'
} catch {
  # A standard user may be unable to register a task. The per-user Startup
  # folder provides the same login-time behavior without elevation.
  $startup = [Environment]::GetFolderPath('Startup')
  $shortcutPath = Join-Path $startup '执手 · 每日数据调度.lnk'
  $pythonw = Join-Path (Split-Path -Parent $python) 'pythonw.exe'
  $shell = New-Object -ComObject WScript.Shell
  $shortcut = $shell.CreateShortcut($shortcutPath)
  $shortcut.TargetPath = $pythonw
  $shortcut.Arguments = '-B "' + $script + '" --daemon'
  $shortcut.WorkingDirectory = $PSScriptRoot
  $shortcut.Description = '执手本机调度：每天北京时间06:00同步昨日店铺数据'
  $shortcut.WindowStyle = 7
  $shortcut.Save()
  $installedBy = '当前用户启动文件夹'
}
Write-Output "installed $taskName (Asia/Shanghai 06:00) via $installedBy"
