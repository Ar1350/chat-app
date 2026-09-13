# jpackage 生成的启动器只能是英文名，本脚本把 exe 与同名 cfg 成对改成中文显示名
# 由 build-windows.bat 调用，也可单独执行：powershell -ExecutionPolicy Bypass -File package\rename-launchers.ps1
$ErrorActionPreference = "Stop"
$root = Join-Path $PSScriptRoot "..\dist\JiaJiaChat"
$root = [System.IO.Path]::GetFullPath($root)
if (-not (Test-Path $root)) { Write-Host "app-image 不存在: $root"; exit 1 }

$nClient = -join ([char[]](0x4F73,0x4F73,0x804A,0x5929))                 # 佳佳聊天
$srvTail = -join ([char[]](0x670D,0x52A1,0x7AEF))                        # 服务端
$nServer = $nClient + "-" + $srvTail
$nAdmin  = -join ([char[]](0x4F73,0x4F73,0x7BA1,0x7406,0x540E,0x53F0))   # 佳佳管理后台

function Rename-Pair($key, $newName) {
    $exe = Join-Path $root "$key.exe"
    $cfg = Join-Path $root "app\$key.cfg"
    if (Test-Path $exe) { Rename-Item $exe ($newName + ".exe") -Force }
    if (Test-Path $cfg) { Rename-Item $cfg ($newName + ".cfg") -Force }
}
Rename-Pair "client" $nClient
Rename-Pair "server" $nServer
Rename-Pair "admin"  $nAdmin
Write-Host "启动器已改名: $nClient / $nServer / $nAdmin"
