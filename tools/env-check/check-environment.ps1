# 開発環境が整っているかを確かめる（98_実装計画書 3章）
# 使い方：PowerShell で次を実行する（リポジトリのフォルダで）
#   powershell -ExecutionPolicy Bypass -File tools\env-check\check-environment.ps1 -OutFile ..\env-result.txt
#   （結果のファイルはリポジトリの外に置く。check-*.cmd をダブルクリックしてもよい）
param(
    # 結果を保存するファイル（省略すると画面に出すだけ）
    [string]$OutFile = ''
)

. (Join-Path $PSScriptRoot 'EnvCheck.Lib.ps1')

$settings = Get-DefaultSettings
$results = Invoke-EnvironmentCheck -Settings $settings
$lines = Format-CheckReport -Title '開発環境の確認' -Results $results
$lines | ForEach-Object { Write-Output $_ }
if ($OutFile -ne '') {
    $lines | Out-File -FilePath $OutFile -Encoding utf8
}
exit (Get-CheckExitCode -Results $results)
