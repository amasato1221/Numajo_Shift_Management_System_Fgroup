# テスト用の置き換え（EnvCheck.Lib.ps1 の「外とのやりとり」の関数を、決めた値を返す関数にする）
# EnvCheck.Lib.ps1 を読み込んだあとで読み込む。

function Reset-Fake {
    $script:Fake = @{
        Tools    = [ordered]@{}
        Files    = @{}
        FileText = @{}
        Env      = @{}
        Os       = $null
        Services = @{}
        Ports    = @{}
        Urls     = @{}
        Calls    = New-Object System.Collections.ArrayList
        OptionFileTexts = New-Object System.Collections.ArrayList
    }
}

# コマンドの結果を決める。Pattern は -like の形（* が使える）。先に登録したものから順に比べる
function Set-FakeTool {
    param([string]$Pattern, [int]$ExitCode, [string]$Output = '')
    $script:Fake.Tools[$Pattern] = [pscustomobject]@{ ExitCode = $ExitCode; Output = $Output }
}

function Invoke-Tool {
    param([string]$FilePath, [string[]]$Arguments = @())
    $key = ($FilePath + ' ' + ($Arguments -join ' ')).Trim()
    [void]$script:Fake.Calls.Add($key)
    # mysql のオプションファイルの中身を、消される前に控える
    foreach ($argument in $Arguments) {
        if ($argument -like '--defaults-extra-file=*') {
            $path = $argument.Substring('--defaults-extra-file='.Length)
            [void]$script:Fake.OptionFileTexts.Add([pscustomobject]@{ Path = $path; Text = [System.IO.File]::ReadAllText($path) })
        }
    }
    foreach ($pattern in $script:Fake.Tools.Keys) {
        if ($key -like $pattern) {
            return $script:Fake.Tools[$pattern]
        }
    }
    return [pscustomobject]@{ ExitCode = -1; Output = '' }
}

function Test-FileExists {
    param([string]$Path)
    return $script:Fake.Files.ContainsKey($Path)
}

function Get-FileText {
    param([string]$Path)
    return $script:Fake.FileText[$Path]
}

function Get-EnvValue {
    param([string]$Name)
    return $script:Fake.Env[$Name]
}

function Test-HttpsReachable {
    param([string]$Url)
    return ($script:Fake.Urls[$Url] -eq $true)
}

function Get-Timestamp {
    return '20261022093000'
}

function Get-OsInfo {
    return $script:Fake.Os
}

function Get-ServiceStatus {
    param([string]$Name)
    return $script:Fake.Services[$Name]
}

function Test-PortListening {
    param([int]$Port)
    return ($script:Fake.Ports[$Port] -eq $true)
}

# 全部 OK になる環境を作る（各テストはここから1か所だけ変える）
function Set-FakeHealthyEnvironment {
    param([hashtable]$Settings)
    Reset-Fake
    $script:Fake.Os = [pscustomobject]@{ Caption = 'Microsoft Windows 11 Pro'; Build = 26100 }
    Set-FakeTool -Pattern 'java -version' -ExitCode 0 -Output "java version `"22.0.1`" 2024-04-16`nJava(TM) SE Runtime Environment"
    $script:Fake.Env['JAVA_HOME'] = 'C:\Program Files\Java\jdk-22'
    $javaExe = [System.IO.Path]::Combine('C:\Program Files\Java\jdk-22', 'bin', 'java.exe')
    $script:Fake.Files[$javaExe] = $true
    Set-FakeTool -Pattern "$javaExe -version" -ExitCode 0 -Output 'java version "22.0.1" 2024-04-16'
    Set-FakeTool -Pattern 'git --version' -ExitCode 0 -Output 'git version 2.54.0.windows.1'
    Set-FakeTool -Pattern 'git config --global --get user.name' -ExitCode 0 -Output 'Arai'
    Set-FakeTool -Pattern 'git config --global --get user.email' -ExitCode 0 -Output 'arai@example.com'
    Set-FakeTool -Pattern 'git config --global --get core.autocrlf' -ExitCode 0 -Output 'false'
    Set-FakeTool -Pattern 'git config --global --get core.quotepath' -ExitCode 0 -Output 'false'
    $script:Fake.Files[$Settings.MySqlExe] = $true
    Set-FakeTool -Pattern "$($Settings.MySqlExe) --version" -ExitCode 0 -Output 'C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe  Ver 8.4.5 for Win64 on x86_64 (MySQL Community Server - GPL)'
    $script:Fake.Services[$Settings.MySqlService] = 'Running'
    $script:Fake.Env['DB_PASSWORD'] = 'pass"word\1'
    Set-FakeTool -Pattern "$($Settings.MySqlExe) --defaults-extra-file=* SELECT SCHEMA_NAME *" -ExitCode 0 -Output "shift_management`nshift_test"
    Set-FakeTool -Pattern "$($Settings.MySqlExe) --defaults-extra-file=* SHOW GRANTS" -ExitCode 0 -Output "GRANT USAGE ON *.* TO ``shift_app``@``localhost```nGRANT SHOW_ROUTINE ON *.* TO ``shift_app``@``localhost``"
    $script:Fake.Files[[System.IO.Path]::Combine($Settings.EclipseDir, 'eclipse.exe')] = $true
    $script:Fake.Files[[System.IO.Path]::Combine($Settings.EclipseDir, 'eclipse.ini')] = $true
    $script:Fake.FileText[[System.IO.Path]::Combine($Settings.EclipseDir, 'eclipse.ini')] = "-vmargs`n-javaagent:C:\pleiades\2025-12\eclipse\lombok.jar"
    $script:Fake.Ports[$Settings.AppPort] = $false
    $script:Fake.Files[$Settings.BackupDir] = $true
    foreach ($url in $Settings.Urls) {
        $script:Fake.Urls[$url] = $true
    }
}

# 全部 OK になるリポジトリを作る
function Set-FakeHealthyRepository {
    param([hashtable]$Settings, [string]$RepoPath)
    Reset-Fake
    $script:Fake.Files[$RepoPath] = $true
    Set-FakeTool -Pattern "git -C $RepoPath rev-parse --is-inside-work-tree" -ExitCode 0 -Output 'true'
    Set-FakeTool -Pattern "git -C $RepoPath remote get-url origin" -ExitCode 0 -Output 'https://github.com/amasato1221/numajo_shift_management_system_fgroup.git'
    Set-FakeTool -Pattern "git -C $RepoPath fetch origin --prune" -ExitCode 0
    Set-FakeTool -Pattern "git -C $RepoPath rev-list --left-right --count main...origin/main" -ExitCode 0 -Output "0`t0"
    Set-FakeTool -Pattern 'git config --global --get user.name' -ExitCode 0 -Output 'Kurihara'
    Set-FakeTool -Pattern "git -C $RepoPath push origin refs/remotes/origin/main:refs/heads/*" -ExitCode 0
    Set-FakeTool -Pattern "git -C $RepoPath push origin --delete *" -ExitCode 0
    Set-FakeTool -Pattern "git -C $RepoPath check-ignore -q $($Settings.LogoPath)" -ExitCode 0
    $script:Fake.Files[[System.IO.Path]::Combine($RepoPath, $Settings.LogoPath)] = $true
}
