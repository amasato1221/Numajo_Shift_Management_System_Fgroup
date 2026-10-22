# リポジトリと連携できているか（clone・fetch・プッシュ）を確かめる（98_実装計画書 3章）
# 使い方：PowerShell で次を実行する（リポジトリのフォルダで）
#   powershell -ExecutionPolicy Bypass -File tools\env-check\check-repository.ps1 -OutFile ..\repo-result.txt
#   （結果のファイルはリポジトリの外に置く。check-*.cmd をダブルクリックしてもよい）
# 招待を受ける前は -SkipPush を付けると、プッシュ以外を確かめられる。
param(
    # リポジトリのフォルダ（省略するとこのスクリプトの2つ上のフォルダ）
    [string]$RepoPath = '',
    # プッシュの確認をしない
    [switch]$SkipPush,
    # 結果を保存するファイル（省略すると画面に出すだけ）
    [string]$OutFile = ''
)

. (Join-Path $PSScriptRoot 'EnvCheck.Lib.ps1')

if ($RepoPath -eq '') {
    $RepoPath = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
}
$settings = Get-DefaultSettings
$results = Invoke-RepositoryCheck -RepoPath $RepoPath -Settings $settings -SkipPush:$SkipPush
$lines = Format-CheckReport -Title 'リポジトリ連携の確認' -Results $results
$lines | ForEach-Object { Write-Output $_ }
if ($OutFile -ne '') {
    $lines | Out-File -FilePath $OutFile -Encoding utf8
}
exit (Get-CheckExitCode -Results $results)
