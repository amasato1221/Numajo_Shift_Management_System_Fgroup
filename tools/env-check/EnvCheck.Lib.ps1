# 開発環境とリポジトリ連携を確かめる関数（98_実装計画書 3章）
# check-environment.ps1・check-repository.ps1 から読み込んで使う。
# Windows PowerShell 5.1 で動くように書く（三項演算子・?? は使わない）。

# ---------------------------------------------------------------
# 1. 設定と結果
# ---------------------------------------------------------------

# 確かめる値の既定値（96_環境構築手順書・10_基本設計書 2.2 の版）
function Get-DefaultSettings {
    return @{
        JavaVersion    = '22.0.1'
        GitVersion     = '2.54'
        MySqlExe       = 'C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe'
        MySqlVersion   = '8.4.5'
        MySqlService   = 'MySQL84'
        DbUser         = 'shift_app'
        Databases      = @('shift_management', 'shift_test')
        EclipseDir     = 'C:\pleiades\2025-12\eclipse'
        AppPort        = 8080
        BackupDir      = 'C:\shift-management\backup'
        Urls           = @('https://github.com/', 'https://repo.maven.apache.org/maven2/')
        RepoUrl        = 'https://github.com/amasato1221/numajo_shift_management_system_fgroup'
        LogoPath       = 'src/main/resources/static/images/logo.png'
    }
}

# 1件の確認結果を作る。Status は OK・WARN（注意）・NG・SKIP（未確認）
function New-CheckResult {
    param(
        [string]$Id,
        [string]$Name,
        [string]$Status,
        [string]$Detail,
        [string]$Fix = ''
    )
    return [pscustomobject]@{
        Id     = $Id
        Name   = $Name
        Status = $Status
        Detail = $Detail
        Fix    = $Fix
    }
}

# 前の項目が NG のため確かめられなかった結果を作る
function New-SkipResult {
    param([string]$Id, [string]$Name, [string]$Reason)
    return New-CheckResult -Id $Id -Name $Name -Status 'SKIP' -Detail $Reason -Fix '前の項目を直してから、もう一度実行してください'
}

# ---------------------------------------------------------------
# 2. 外とのやりとり（テストでは同じ名前の関数で置き換える）
# ---------------------------------------------------------------

# コマンドを実行し、終了コードと出力（標準エラーを含む）を返す。コマンドがないときは ExitCode -1
function Invoke-Tool {
    param([string]$FilePath, [string[]]$Arguments = @())
    $command = Get-Command $FilePath -ErrorAction SilentlyContinue
    if ($null -eq $command) {
        return [pscustomobject]@{ ExitCode = -1; Output = '' }
    }
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $lines = & $FilePath @Arguments 2>&1 | ForEach-Object { "$_" }
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    return [pscustomobject]@{ ExitCode = $code; Output = (@($lines) -join "`n") }
}

function Test-FileExists {
    param([string]$Path)
    return (Test-Path -LiteralPath $Path)
}

function Get-FileText {
    param([string]$Path)
    return (Get-Content -LiteralPath $Path -Raw -Encoding UTF8)
}

function Get-EnvValue {
    param([string]$Name)
    return [Environment]::GetEnvironmentVariable($Name)
}

# HTTPS で相手に届くか。HTTP のエラー（403・404 など）でも返事が来れば届いたとみなす
function Test-HttpsReachable {
    param([string]$Url)
    try {
        $null = Invoke-WebRequest -Uri $Url -Method Head -UseBasicParsing -TimeoutSec 15
        return $true
    } catch {
        return ($null -ne $_.Exception.Response)
    }
}

function Get-Timestamp {
    return (Get-Date -Format 'yyyyMMddHHmmss')
}

# --- ここから下の3つは Windows でだけ動く（Linux のテストでは置き換える。98_実装計画書 3.4） ---

function Get-OsInfo {
    $os = Get-CimInstance -ClassName Win32_OperatingSystem
    return [pscustomobject]@{ Caption = [string]$os.Caption; Build = [int]$os.BuildNumber }
}

function Get-ServiceStatus {
    param([string]$Name)
    $service = Get-Service -Name $Name -ErrorAction SilentlyContinue
    if ($null -eq $service) {
        return $null
    }
    return [string]$service.Status
}

function Test-PortListening {
    param([int]$Port)
    $connections = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    return ($null -ne $connections)
}

# ---------------------------------------------------------------
# 3. 開発環境の確認（E01〜E15）
# ---------------------------------------------------------------

# E01 Windows 11 か（ビルド番号 22000 以上）
function Test-OsVersion {
    $os = Get-OsInfo
    if ($os.Build -ge 22000) {
        return New-CheckResult -Id 'E01' -Name 'Windows の版' -Status 'OK' -Detail $os.Caption
    }
    return New-CheckResult -Id 'E01' -Name 'Windows の版' -Status 'WARN' -Detail $os.Caption -Fix '手順書は Windows 11 が前提です。違うところがないか新井さんに相談してください'
}

# java -version の出力から版を取り出す。取り出せないときは $null
function Get-JavaVersionFromText {
    param([string]$Text)
    if ($Text -match 'version "([^"]+)"') {
        return $Matches[1]
    }
    return $null
}

# E02 java コマンドの版が 22.0.1 か
function Test-JavaVersion {
    param([hashtable]$Settings)
    $fix = 'Oracle JDK 22.0.1 を入れ、PATH の先頭にしてください（96_環境構築手順書 2.2）'
    $result = Invoke-Tool -FilePath 'java' -Arguments @('-version')
    if ($result.ExitCode -ne 0) {
        return New-CheckResult -Id 'E02' -Name 'Java の版' -Status 'NG' -Detail 'java コマンドが見つからないか、動きません' -Fix $fix
    }
    $version = Get-JavaVersionFromText -Text $result.Output
    if ($version -eq $Settings.JavaVersion) {
        return New-CheckResult -Id 'E02' -Name 'Java の版' -Status 'OK' -Detail $version
    }
    return New-CheckResult -Id 'E02' -Name 'Java の版' -Status 'NG' -Detail "版が違います（今：$version、正：$($Settings.JavaVersion)）" -Fix $fix
}

# E03 JAVA_HOME が JDK 22.0.1 を指しているか（mvnw.cmd が使う）
function Test-JavaHome {
    param([hashtable]$Settings)
    $fix = 'ユーザー環境変数 JAVA_HOME を C:\Program Files\Java\jdk-22 にし、PowerShell を開き直してください（96_環境構築手順書 2.3）'
    $javaHome = Get-EnvValue -Name 'JAVA_HOME'
    if ([string]::IsNullOrEmpty($javaHome)) {
        return New-CheckResult -Id 'E03' -Name 'JAVA_HOME' -Status 'NG' -Detail 'JAVA_HOME がありません' -Fix $fix
    }
    $javaExe = [System.IO.Path]::Combine($javaHome, 'bin', 'java.exe')
    if (-not (Test-FileExists -Path $javaExe)) {
        return New-CheckResult -Id 'E03' -Name 'JAVA_HOME' -Status 'NG' -Detail "$javaExe がありません" -Fix $fix
    }
    $result = Invoke-Tool -FilePath $javaExe -Arguments @('-version')
    $version = Get-JavaVersionFromText -Text $result.Output
    if ($version -eq $Settings.JavaVersion) {
        return New-CheckResult -Id 'E03' -Name 'JAVA_HOME' -Status 'OK' -Detail "$javaHome（$version）"
    }
    return New-CheckResult -Id 'E03' -Name 'JAVA_HOME' -Status 'NG' -Detail "$javaHome の版が違います（今：$version）" -Fix $fix
}

# E04 Git の版が 2.54 か
function Test-GitVersion {
    param([hashtable]$Settings)
    $result = Invoke-Tool -FilePath 'git' -Arguments @('--version')
    if ($result.ExitCode -ne 0) {
        return New-CheckResult -Id 'E04' -Name 'Git の版' -Status 'NG' -Detail 'git コマンドが見つかりません' -Fix 'Git for Windows 2.54 を入れてください（96_環境構築手順書 2.2）'
    }
    if ($result.Output -match 'git version (\d+\.\d+)') {
        $version = $Matches[1]
    } else {
        $version = '不明'
    }
    if ($version -eq $Settings.GitVersion) {
        return New-CheckResult -Id 'E04' -Name 'Git の版' -Status 'OK' -Detail $result.Output.Trim()
    }
    return New-CheckResult -Id 'E04' -Name 'Git の版' -Status 'WARN' -Detail "版が違います（今：$version、正：$($Settings.GitVersion)）" -Fix '動くことは多いですが、そろえるため Git for Windows 2.54 を入れ直してください'
}

# git config --global の値を1つ読む（ないときは空文字）
function Get-GitGlobalConfig {
    param([string]$Key)
    $result = Invoke-Tool -FilePath 'git' -Arguments @('config', '--global', '--get', $Key)
    if ($result.ExitCode -ne 0) {
        return ''
    }
    return $result.Output.Trim()
}

# E05 コミットに使う名前とメールアドレスがあるか
function Test-GitIdentity {
    $userName = Get-GitGlobalConfig -Key 'user.name'
    $userEmail = Get-GitGlobalConfig -Key 'user.email'
    if ($userName -ne '' -and $userEmail -ne '') {
        return New-CheckResult -Id 'E05' -Name 'Git の名前とメール' -Status 'OK' -Detail "$userName <$userEmail>"
    }
    $missing = @()
    if ($userName -eq '') {
        $missing += 'user.name'
    }
    if ($userEmail -eq '') {
        $missing += 'user.email'
    }
    return New-CheckResult -Id 'E05' -Name 'Git の名前とメール' -Status 'NG' -Detail ("ありません：" + ($missing -join '、')) -Fix 'git config --global で設定してください（96_環境構築手順書 3章）'
}

# E06 改行コードと日本語ファイル名の設定
function Test-GitSettings {
    $autoCrlf = Get-GitGlobalConfig -Key 'core.autocrlf'
    $quotePath = Get-GitGlobalConfig -Key 'core.quotepath'
    if ($autoCrlf -eq 'false' -and $quotePath -eq 'false') {
        return New-CheckResult -Id 'E06' -Name 'Git の改行・表示設定' -Status 'OK' -Detail 'core.autocrlf=false、core.quotepath=false'
    }
    if ($autoCrlf -ne 'false') {
        return New-CheckResult -Id 'E06' -Name 'Git の改行・表示設定' -Status 'NG' -Detail "core.autocrlf が false ではありません（今：$autoCrlf）" -Fix 'git config --global core.autocrlf false（改行がずれてコンフリクトの原因になります。96_環境構築手順書 3章）'
    }
    return New-CheckResult -Id 'E06' -Name 'Git の改行・表示設定' -Status 'WARN' -Detail "core.quotepath が false ではありません（今：$quotePath）" -Fix 'git config --global core.quotepath false（日本語のファイル名が読めるようになります）'
}

# E07 MySQL の版が 8.4.5 か
function Test-MySqlVersion {
    param([hashtable]$Settings)
    $fix = 'MySQL 8.4.5 を MySQL Installer で入れてください（96_環境構築手順書 2.2）'
    if (-not (Test-FileExists -Path $Settings.MySqlExe)) {
        return New-CheckResult -Id 'E07' -Name 'MySQL の版' -Status 'NG' -Detail "$($Settings.MySqlExe) がありません" -Fix $fix
    }
    $result = Invoke-Tool -FilePath $Settings.MySqlExe -Arguments @('--version')
    if ($result.Output -match 'Ver (\d+\.\d+\.\d+)') {
        $version = $Matches[1]
    } else {
        $version = '不明'
    }
    if ($version -eq $Settings.MySqlVersion) {
        return New-CheckResult -Id 'E07' -Name 'MySQL の版' -Status 'OK' -Detail $version
    }
    if ($version.StartsWith('8.4.')) {
        return New-CheckResult -Id 'E07' -Name 'MySQL の版' -Status 'WARN' -Detail "8.4 系ですが版が違います（今：$version、正：$($Settings.MySqlVersion)）" -Fix 'そろえるため 8.4.5 にしてください'
    }
    return New-CheckResult -Id 'E07' -Name 'MySQL の版' -Status 'NG' -Detail "版が違います（今：$version）" -Fix $fix
}

# E08 MySQL のサービスが動いているか
function Test-MySqlService {
    param([hashtable]$Settings)
    $status = Get-ServiceStatus -Name $Settings.MySqlService
    if ($null -eq $status) {
        return New-CheckResult -Id 'E08' -Name 'MySQL のサービス' -Status 'NG' -Detail "サービス $($Settings.MySqlService) がありません" -Fix 'MySQL を入れるときのサービス名を MySQL84 にしてください（96_環境構築手順書 2.2）'
    }
    if ($status -eq 'Running') {
        return New-CheckResult -Id 'E08' -Name 'MySQL のサービス' -Status 'OK' -Detail "$($Settings.MySqlService) は動いています"
    }
    return New-CheckResult -Id 'E08' -Name 'MySQL のサービス' -Status 'NG' -Detail "$($Settings.MySqlService) が止まっています（$status）" -Fix '管理者の PowerShell で Start-Service MySQL84'
}

# E09 環境変数 DB_PASSWORD があるか（値は表示しない。94_セキュリティ設計書 3.10）
function Test-DbPassword {
    $password = Get-EnvValue -Name 'DB_PASSWORD'
    if ([string]::IsNullOrEmpty($password)) {
        return New-CheckResult -Id 'E09' -Name '環境変数 DB_PASSWORD' -Status 'NG' -Detail 'DB_PASSWORD がありません' -Fix 'ユーザー環境変数 DB_PASSWORD を作り、PowerShell を開き直してください（96_環境構築手順書 2.3）'
    }
    return New-CheckResult -Id 'E09' -Name '環境変数 DB_PASSWORD' -Status 'OK' -Detail 'あります（値は表示しません）'
}

# shift_app で SQL を1つ実行する。パスワードはコマンドラインに出さず、一時ファイルで渡してすぐ消す
function Invoke-MySqlQuery {
    param([hashtable]$Settings, [string]$Sql)
    $password = Get-EnvValue -Name 'DB_PASSWORD'
    $escaped = $password.Replace('\', '\\').Replace('"', '\"')
    $optionFile = [System.IO.Path]::GetTempFileName()
    try {
        $text = "[client]`npassword=`"$escaped`"`n"
        [System.IO.File]::WriteAllText($optionFile, $text, (New-Object System.Text.UTF8Encoding $false))
        $arguments = @("--defaults-extra-file=$optionFile", '-u', $Settings.DbUser, '-h', 'localhost', '-N', '-B', '-e', $Sql)
        return Invoke-Tool -FilePath $Settings.MySqlExe -Arguments $arguments
    } finally {
        Remove-Item -LiteralPath $optionFile -Force -ErrorAction SilentlyContinue
    }
}

# E10 shift_app で接続でき、本番用とテスト用の2つの DB があるか
function Test-DbConnection {
    param([hashtable]$Settings)
    $names = ($Settings.Databases | ForEach-Object { "'$_'" }) -join ','
    $result = Invoke-MySqlQuery -Settings $Settings -Sql "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME IN ($names)"
    if ($result.ExitCode -ne 0) {
        return New-CheckResult -Id 'E10' -Name 'DB への接続' -Status 'NG' -Detail ("接続できません：" + $result.Output.Trim()) -Fix 'shift_app のユーザーとパスワード（DB_PASSWORD と同じか）を確かめてください（96_環境構築手順書 6.1、10章 No.1）'
    }
    $found = @($result.Output -split "`n" | ForEach-Object { $_.Trim() })
    $missing = @($Settings.Databases | Where-Object { $found -notcontains $_ })
    if ($missing.Count -eq 0) {
        return New-CheckResult -Id 'E10' -Name 'DB への接続' -Status 'OK' -Detail ("接続できました：" + ($Settings.Databases -join '、'))
    }
    return New-CheckResult -Id 'E10' -Name 'DB への接続' -Status 'NG' -Detail ("DB がありません：" + ($missing -join '、')) -Fix 'CREATE DATABASE と GRANT を実行してください（96_環境構築手順書 6.1）'
}

# E11 バックアップ（mysqldump --routines）に必要な SHOW_ROUTINE の権限があるか
function Test-DbBackupGrant {
    param([hashtable]$Settings)
    $result = Invoke-MySqlQuery -Settings $Settings -Sql 'SHOW GRANTS'
    if ($result.ExitCode -eq 0 -and $result.Output -match 'SHOW_ROUTINE') {
        return New-CheckResult -Id 'E11' -Name 'バックアップの権限' -Status 'OK' -Detail 'SHOW_ROUTINE があります'
    }
    return New-CheckResult -Id 'E11' -Name 'バックアップの権限' -Status 'WARN' -Detail 'SHOW_ROUTINE の権限がありません' -Fix "root で GRANT SHOW_ROUTINE ON *.* TO 'shift_app'@'localhost'; を実行してください（96_環境構築手順書 6.1）"
}

# E12 Eclipse（Pleiades）と Lombok
function Test-Eclipse {
    param([hashtable]$Settings)
    $eclipseExe = [System.IO.Path]::Combine($Settings.EclipseDir, 'eclipse.exe')
    $eclipseIni = [System.IO.Path]::Combine($Settings.EclipseDir, 'eclipse.ini')
    if (-not (Test-FileExists -Path $eclipseExe)) {
        return New-CheckResult -Id 'E12' -Name 'Eclipse と Lombok' -Status 'NG' -Detail "$eclipseExe がありません" -Fix 'Pleiades 2025-12 Java Full Edition を C:\pleiades\2025-12 に解凍してください（96_環境構築手順書 2.2）'
    }
    if ((Test-FileExists -Path $eclipseIni) -and ((Get-FileText -Path $eclipseIni) -match '-javaagent:.*lombok')) {
        return New-CheckResult -Id 'E12' -Name 'Eclipse と Lombok' -Status 'OK' -Detail 'Eclipse があり、Lombok が有効です'
    }
    return New-CheckResult -Id 'E12' -Name 'Eclipse と Lombok' -Status 'NG' -Detail 'eclipse.ini に Lombok（-javaagent）の行がありません' -Fix 'Pleiades の Eclipse を使っているか確かめてください（96_環境構築手順書 10章 No.4）'
}

# E13 アプリが使う 8080 番が空いているか
function Test-AppPort {
    param([hashtable]$Settings)
    if (Test-PortListening -Port $Settings.AppPort) {
        return New-CheckResult -Id 'E13' -Name "ポート $($Settings.AppPort)" -Status 'WARN' -Detail 'ほかのプログラムが使っています' -Fix 'アプリがもう動いていないか確かめ、止めてください（96_環境構築手順書 10章 No.3）'
    }
    return New-CheckResult -Id 'E13' -Name "ポート $($Settings.AppPort)" -Status 'OK' -Detail '空いています'
}

# E14 バックアップの保存先
function Test-BackupDir {
    param([hashtable]$Settings)
    if (Test-FileExists -Path $Settings.BackupDir) {
        return New-CheckResult -Id 'E14' -Name 'バックアップの保存先' -Status 'OK' -Detail $Settings.BackupDir
    }
    return New-CheckResult -Id 'E14' -Name 'バックアップの保存先' -Status 'NG' -Detail "$($Settings.BackupDir) がありません" -Fix "mkdir $($Settings.BackupDir)（96_環境構築手順書 6.2）"
}

# E15 GitHub と Maven Central（ライブラリのダウンロード先）に届くか
function Test-Network {
    param([hashtable]$Settings)
    $unreachable = @($Settings.Urls | Where-Object { -not (Test-HttpsReachable -Url $_) })
    if ($unreachable.Count -eq 0) {
        return New-CheckResult -Id 'E15' -Name 'インターネット接続' -Status 'OK' -Detail ("届きました：" + ($Settings.Urls -join '、'))
    }
    return New-CheckResult -Id 'E15' -Name 'インターネット接続' -Status 'NG' -Detail ("届きません：" + ($unreachable -join '、')) -Fix '学校のネットワークでつながらないときは、プロキシの設定を先生に確かめてください'
}

# E01〜E15 をまとめて実行する
function Invoke-EnvironmentCheck {
    param([hashtable]$Settings)
    $results = @()
    $results += Test-OsVersion
    $results += Test-JavaVersion -Settings $Settings
    $results += Test-JavaHome -Settings $Settings
    $results += Test-GitVersion -Settings $Settings
    $results += Test-GitIdentity
    $results += Test-GitSettings
    $mysqlVersion = Test-MySqlVersion -Settings $Settings
    $results += $mysqlVersion
    $mysqlService = Test-MySqlService -Settings $Settings
    $results += $mysqlService
    $dbPassword = Test-DbPassword
    $results += $dbPassword
    if ($mysqlVersion.Status -ne 'NG' -and $mysqlService.Status -eq 'OK' -and $dbPassword.Status -eq 'OK') {
        $results += Test-DbConnection -Settings $Settings
        $results += Test-DbBackupGrant -Settings $Settings
    } else {
        $results += New-SkipResult -Id 'E10' -Name 'DB への接続' -Reason 'E07〜E09 のどれかが NG のため確かめていません'
        $results += New-SkipResult -Id 'E11' -Name 'バックアップの権限' -Reason 'E07〜E09 のどれかが NG のため確かめていません'
    }
    $results += Test-Eclipse -Settings $Settings
    $results += Test-AppPort -Settings $Settings
    $results += Test-BackupDir -Settings $Settings
    $results += Test-Network -Settings $Settings
    return $results
}

# ---------------------------------------------------------------
# 4. リポジトリ連携の確認（R01〜R06）
# ---------------------------------------------------------------

# git -C <リポジトリ> を実行する
function Invoke-RepoGit {
    param([string]$RepoPath, [string[]]$Arguments)
    return Invoke-Tool -FilePath 'git' -Arguments (@('-C', $RepoPath) + $Arguments)
}

# R01 フォルダがあり、Git のリポジトリになっているか
function Test-RepoFolder {
    param([string]$RepoPath)
    $fix = 'Eclipse のワークスペースで git clone してください（96_環境構築手順書 3章）'
    if (-not (Test-FileExists -Path $RepoPath)) {
        return New-CheckResult -Id 'R01' -Name 'リポジトリのフォルダ' -Status 'NG' -Detail "$RepoPath がありません" -Fix $fix
    }
    $result = Invoke-RepoGit -RepoPath $RepoPath -Arguments @('rev-parse', '--is-inside-work-tree')
    if ($result.ExitCode -eq 0 -and $result.Output.Trim() -eq 'true') {
        return New-CheckResult -Id 'R01' -Name 'リポジトリのフォルダ' -Status 'OK' -Detail $RepoPath
    }
    return New-CheckResult -Id 'R01' -Name 'リポジトリのフォルダ' -Status 'NG' -Detail "$RepoPath は Git のリポジトリではありません" -Fix $fix
}

# URL の比べ方をそろえる（大文字小文字・末尾の .git と / を無視する。GitHub は大文字小文字を区別しない）
function ConvertTo-NormalizedRepoUrl {
    param([string]$Url)
    $normalized = $Url.Trim().ToLowerInvariant()
    if ($normalized.EndsWith('/')) {
        $normalized = $normalized.Substring(0, $normalized.Length - 1)
    }
    if ($normalized.EndsWith('.git')) {
        $normalized = $normalized.Substring(0, $normalized.Length - 4)
    }
    return $normalized
}

# R02 origin が班のリポジトリを指しているか
function Test-RepoRemote {
    param([string]$RepoPath, [hashtable]$Settings)
    $result = Invoke-RepoGit -RepoPath $RepoPath -Arguments @('remote', 'get-url', 'origin')
    $actual = ConvertTo-NormalizedRepoUrl -Url $result.Output
    $expected = ConvertTo-NormalizedRepoUrl -Url $Settings.RepoUrl
    if ($result.ExitCode -eq 0 -and $actual -eq $expected) {
        return New-CheckResult -Id 'R02' -Name 'リモート（origin）' -Status 'OK' -Detail $result.Output.Trim()
    }
    return New-CheckResult -Id 'R02' -Name 'リモート（origin）' -Status 'NG' -Detail ("origin が違います：" + $result.Output.Trim()) -Fix "git remote set-url origin $($Settings.RepoUrl).git"
}

# R03 GitHub から最新を取ってこられるか
function Test-RepoFetch {
    param([string]$RepoPath)
    $result = Invoke-RepoGit -RepoPath $RepoPath -Arguments @('fetch', 'origin', '--prune')
    if ($result.ExitCode -eq 0) {
        return New-CheckResult -Id 'R03' -Name 'GitHub から取得（fetch）' -Status 'OK' -Detail 'できました'
    }
    return New-CheckResult -Id 'R03' -Name 'GitHub から取得（fetch）' -Status 'NG' -Detail ("できません：" + $result.Output.Trim()) -Fix 'インターネット接続（E15）と GitHub へのサインインを確かめてください'
}

# R04 手元の main が GitHub の main と同じか（main に直接コミットしていないか。92_Git運用規約 2.1）
function Test-RepoMainSync {
    param([string]$RepoPath)
    $result = Invoke-RepoGit -RepoPath $RepoPath -Arguments @('rev-list', '--left-right', '--count', 'main...origin/main')
    if ($result.ExitCode -ne 0 -or $result.Output.Trim() -notmatch '^(\d+)\s+(\d+)$') {
        return New-CheckResult -Id 'R04' -Name 'main が最新か' -Status 'NG' -Detail '手元に main ブランチがありません' -Fix 'git switch main を実行してください'
    }
    $ahead = [int]$Matches[1]
    $behind = [int]$Matches[2]
    if ($ahead -gt 0) {
        return New-CheckResult -Id 'R04' -Name 'main が最新か' -Status 'NG' -Detail "手元の main に、GitHub にないコミットが $ahead 件あります" -Fix 'main に直接コミットしてはいけません（92_Git運用規約 2.1）。新井さんに相談してください'
    }
    if ($behind -gt 0) {
        return New-CheckResult -Id 'R04' -Name 'main が最新か' -Status 'WARN' -Detail "GitHub の main より $behind 件遅れています" -Fix 'git switch main → git pull origin main'
    }
    return New-CheckResult -Id 'R04' -Name 'main が最新か' -Status 'OK' -Detail 'GitHub の main と同じです'
}

# プッシュ確認用ブランチの名前に使う文字列（英小文字・数字・ハイフンだけ）
function Get-BranchTag {
    $raw = Get-GitGlobalConfig -Key 'user.name'
    $tag = ($raw.ToLowerInvariant() -replace '[^a-z0-9-]', '')
    if ($tag -eq '') {
        $tag = (([string](Get-EnvValue -Name 'USERNAME')).ToLowerInvariant() -replace '[^a-z0-9-]', '')
    }
    if ($tag -eq '') {
        $tag = 'user'
    }
    return $tag
}

# R05 GitHub にプッシュできるか。main と同じ場所を指す一時ブランチを作り、すぐ消す（ファイルは何も変えない）
function Test-RepoPush {
    param([string]$RepoPath, [switch]$SkipPush)
    if ($SkipPush) {
        return New-CheckResult -Id 'R05' -Name 'GitHub へのプッシュ' -Status 'SKIP' -Detail '-SkipPush を指定したため確かめていません' -Fix '招待を受けたあとで、-SkipPush を付けずに実行してください'
    }
    $branch = 'test/env-check-' + (Get-BranchTag) + '-' + (Get-Timestamp)
    $push = Invoke-RepoGit -RepoPath $RepoPath -Arguments @('push', 'origin', "refs/remotes/origin/main:refs/heads/$branch")
    if ($push.ExitCode -ne 0) {
        if ($push.Output -match '403|denied|not found') {
            $detail = '書き込みの権限がありません'
        } else {
            $detail = 'プッシュできません：' + $push.Output.Trim()
        }
        return New-CheckResult -Id 'R05' -Name 'GitHub へのプッシュ' -Status 'NG' -Detail $detail -Fix '新井さんからの招待（Collaborator）を GitHub で承認し、Git に自分のアカウントでサインインしてください（98_実装計画書 4章）'
    }
    $delete = Invoke-RepoGit -RepoPath $RepoPath -Arguments @('push', 'origin', '--delete', $branch)
    if ($delete.ExitCode -eq 0) {
        return New-CheckResult -Id 'R05' -Name 'GitHub へのプッシュ' -Status 'OK' -Detail "一時ブランチ $branch を作って消せました"
    }
    return New-CheckResult -Id 'R05' -Name 'GitHub へのプッシュ' -Status 'WARN' -Detail "プッシュはできましたが、一時ブランチ $branch が消せませんでした" -Fix 'GitHub の「branches」画面でそのブランチを消してください'
}

# R06 ロゴがリポジトリから除外されていて、手元には置いてあるか（10_基本設計書 5.4）
function Test-RepoLogo {
    param([string]$RepoPath, [hashtable]$Settings)
    $ignored = (Invoke-RepoGit -RepoPath $RepoPath -Arguments @('check-ignore', '-q', $Settings.LogoPath)).ExitCode -eq 0
    $exists = Test-FileExists -Path ([System.IO.Path]::Combine($RepoPath, $Settings.LogoPath))
    if (-not $ignored) {
        return New-CheckResult -Id 'R06' -Name 'ロゴの扱い' -Status 'NG' -Detail 'logo.png が .gitignore で除外されていません' -Fix '.gitignore を main と同じにしてください。公開リポジトリにロゴを入れてはいけません'
    }
    if ($exists) {
        return New-CheckResult -Id 'R06' -Name 'ロゴの扱い' -Status 'OK' -Detail 'logo.png があり、リポジトリからは除外されています'
    }
    return New-CheckResult -Id 'R06' -Name 'ロゴの扱い' -Status 'WARN' -Detail 'logo.png がまだありません（なくてもアプリは動きます）' -Fix '96_環境構築手順書 5.1 の手順で置いてください'
}

# R01〜R06 をまとめて実行する
function Invoke-RepositoryCheck {
    param([string]$RepoPath, [hashtable]$Settings, [switch]$SkipPush)
    $results = @()
    $folder = Test-RepoFolder -RepoPath $RepoPath
    $results += $folder
    if ($folder.Status -ne 'OK') {
        $reason = 'R01 が NG のため確かめていません'
        $results += New-SkipResult -Id 'R02' -Name 'リモート（origin）' -Reason $reason
        $results += New-SkipResult -Id 'R03' -Name 'GitHub から取得（fetch）' -Reason $reason
        $results += New-SkipResult -Id 'R04' -Name 'main が最新か' -Reason $reason
        $results += New-SkipResult -Id 'R05' -Name 'GitHub へのプッシュ' -Reason $reason
        $results += New-SkipResult -Id 'R06' -Name 'ロゴの扱い' -Reason $reason
        return $results
    }
    $results += Test-RepoRemote -RepoPath $RepoPath -Settings $Settings
    $fetch = Test-RepoFetch -RepoPath $RepoPath
    $results += $fetch
    if ($fetch.Status -eq 'OK') {
        $results += Test-RepoMainSync -RepoPath $RepoPath
        $results += Test-RepoPush -RepoPath $RepoPath -SkipPush:$SkipPush
    } else {
        $reason = 'R03 が NG のため確かめていません'
        $results += New-SkipResult -Id 'R04' -Name 'main が最新か' -Reason $reason
        $results += New-SkipResult -Id 'R05' -Name 'GitHub へのプッシュ' -Reason $reason
    }
    $results += Test-RepoLogo -RepoPath $RepoPath -Settings $Settings
    return $results
}

# ---------------------------------------------------------------
# 5. 結果の表示
# ---------------------------------------------------------------

# 表示用の文字（OK・注意・NG・未確認）
function Get-StatusLabel {
    param([string]$Status)
    switch ($Status) {
        'OK' { return 'OK' }
        'WARN' { return '注意' }
        'NG' { return 'NG' }
        default { return '未確認' }
    }
}

# 結果を、画面とファイルに出す行にする
function Format-CheckReport {
    param([string]$Title, [object[]]$Results)
    $lines = @()
    $lines += "===== $Title ====="
    $lines += ('実行した日時：' + (Get-Date -Format 'yyyy/MM/dd HH:mm') + '　PC：' + [Environment]::MachineName)
    foreach ($result in $Results) {
        $lines += ('[' + (Get-StatusLabel -Status $result.Status) + '] ' + $result.Id + ' ' + $result.Name + '：' + $result.Detail)
        if ($result.Status -ne 'OK' -and $result.Fix -ne '') {
            $lines += ('      → ' + $result.Fix)
        }
    }
    $ok = @($Results | Where-Object { $_.Status -eq 'OK' }).Count
    $warn = @($Results | Where-Object { $_.Status -eq 'WARN' }).Count
    $ng = @($Results | Where-Object { $_.Status -eq 'NG' }).Count
    $skip = @($Results | Where-Object { $_.Status -eq 'SKIP' }).Count
    $lines += "合計：OK $ok 件、注意 $warn 件、NG $ng 件、未確認 $skip 件"
    if ($ng -eq 0 -and $skip -eq 0) {
        $lines += '結果：合格です。'
    } else {
        $lines += '結果：不合格です。→ の直し方で直してから、もう一度実行してください。'
    }
    return $lines
}

# 終了コード：NG か未確認が1件でもあれば 1、なければ 0
function Get-CheckExitCode {
    param([object[]]$Results)
    $bad = @($Results | Where-Object { $_.Status -eq 'NG' -or $_.Status -eq 'SKIP' })
    if ($bad.Count -gt 0) {
        return 1
    }
    return 0
}
