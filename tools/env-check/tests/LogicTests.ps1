# 判定のテスト（外とのやりとりは Fakes.ps1 で置き換える）
# Covers には、このテストで通る判定の組み合わせ（README.md 4章の表の ID）を書く。

$S = Get-DefaultSettings
$Repo = 'C:\work\repo'

# 1つの結果の Id と Status を確かめる
function Assert-Result {
    param($Result, [string]$Id, [string]$Status)
    Assert-Equal -Expected $Id -Actual $Result.Id -Message 'Id'
    Assert-Equal -Expected $Status -Actual $Result.Status -Message "$Id の Status（Detail：$($Result.Detail)）"
}

# ---------- 結果の作り方 ----------

Test-Case -Name 'New-CheckResult：Fix を省くと空文字' -Body {
    $r = New-CheckResult -Id 'X' -Name 'n' -Status 'OK' -Detail 'd'
    Assert-Equal -Expected '' -Actual $r.Fix
}

Test-Case -Name 'New-SkipResult：SKIP と直し方が入る' -Body {
    $r = New-SkipResult -Id 'X' -Name 'n' -Reason 'r'
    Assert-Equal -Expected 'SKIP' -Actual $r.Status
    Assert-Equal -Expected 'r' -Actual $r.Detail
    Assert-True -Condition ($r.Fix -ne '')
}

# ---------- E01 Windows の版 ----------

Test-Case -Name 'E01：Windows 11（ビルド 26100）は OK' -Covers 'D01-T' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    Assert-Result (Test-OsVersion) 'E01' 'OK'
}

Test-Case -Name 'E01：境界値 22000 は OK' -Covers 'D01-T' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    $script:Fake.Os = [pscustomobject]@{ Caption = 'Windows 11'; Build = 22000 }
    Assert-Result (Test-OsVersion) 'E01' 'OK'
}

Test-Case -Name 'E01：境界値 21999（Windows 10）は注意' -Covers 'D01-F' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    $script:Fake.Os = [pscustomobject]@{ Caption = 'Windows 10'; Build = 21999 }
    Assert-Result (Test-OsVersion) 'E01' 'WARN'
}

# ---------- E02 Java ----------

Test-Case -Name 'Get-JavaVersionFromText：版を取り出す' -Covers 'D02-T' -Body {
    Assert-Equal -Expected '22.0.1' -Actual (Get-JavaVersionFromText -Text 'openjdk version "22.0.1" 2024-04-16')
}

Test-Case -Name 'Get-JavaVersionFromText：形が違えば null' -Covers 'D02-F' -Body {
    Assert-Null -Value (Get-JavaVersionFromText -Text 'something else')
}

Test-Case -Name 'E02：java が 22.0.1 なら OK' -Covers 'D03-F', 'D04-T' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    Assert-Result (Test-JavaVersion -Settings $S) 'E02' 'OK'
}

Test-Case -Name 'E02：java がない（終了コード -1）なら NG' -Covers 'D03-T' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    $script:Fake.Tools.Remove('java -version')
    Assert-Result (Test-JavaVersion -Settings $S) 'E02' 'NG'
}

Test-Case -Name 'E02：java が 21 なら NG' -Covers 'D03-F', 'D04-F' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    Set-FakeTool -Pattern 'java -version' -ExitCode 0 -Output 'java version "21.0.5" 2024-10-15'
    $r = Test-JavaVersion -Settings $S
    Assert-Result $r 'E02' 'NG'
    Assert-Match -Text $r.Detail -Pattern '21\.0\.5'
}

# ---------- E03 JAVA_HOME ----------

Test-Case -Name 'E03：JAVA_HOME が JDK 22.0.1 なら OK' -Covers 'D05-F', 'D06-F', 'D07-T' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    Assert-Result (Test-JavaHome -Settings $S) 'E03' 'OK'
}

Test-Case -Name 'E03：JAVA_HOME がない（null）なら NG' -Covers 'D05-T' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    $script:Fake.Env.Remove('JAVA_HOME')
    Assert-Result (Test-JavaHome -Settings $S) 'E03' 'NG'
}

Test-Case -Name 'E03：JAVA_HOME が空文字なら NG' -Covers 'D05-T' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    $script:Fake.Env['JAVA_HOME'] = ''
    Assert-Result (Test-JavaHome -Settings $S) 'E03' 'NG'
}

Test-Case -Name 'E03：JAVA_HOME の場所に java.exe がなければ NG' -Covers 'D05-F', 'D06-T' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    $script:Fake.Env['JAVA_HOME'] = 'C:\Java\none'
    Assert-Result (Test-JavaHome -Settings $S) 'E03' 'NG'
}

Test-Case -Name 'E03：JAVA_HOME の JDK が別の版なら NG' -Covers 'D05-F', 'D06-F', 'D07-F' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    $javaExe = [System.IO.Path]::Combine('C:\Program Files\Java\jdk-22', 'bin', 'java.exe')
    Set-FakeTool -Pattern "$javaExe -version" -ExitCode 0 -Output 'java version "22.0.2"'
    Assert-Result (Test-JavaHome -Settings $S) 'E03' 'NG'
}

# ---------- E04 Git の版 ----------

Test-Case -Name 'E04：2.54 なら OK' -Covers 'D08-F', 'D09-T', 'D10-T' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    Assert-Result (Test-GitVersion -Settings $S) 'E04' 'OK'
}

Test-Case -Name 'E04：git がなければ NG' -Covers 'D08-T' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    $script:Fake.Tools.Remove('git --version')
    Assert-Result (Test-GitVersion -Settings $S) 'E04' 'NG'
}

Test-Case -Name 'E04：2.47 なら注意' -Covers 'D08-F', 'D09-T', 'D10-F' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    Set-FakeTool -Pattern 'git --version' -ExitCode 0 -Output 'git version 2.47.1.windows.2'
    Assert-Result (Test-GitVersion -Settings $S) 'E04' 'WARN'
}

Test-Case -Name 'E04：形の違う出力なら注意（版は不明）' -Covers 'D08-F', 'D09-F', 'D10-F' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    Set-FakeTool -Pattern 'git --version' -ExitCode 0 -Output 'unknown'
    $r = Test-GitVersion -Settings $S
    Assert-Result $r 'E04' 'WARN'
    Assert-Match -Text $r.Detail -Pattern '不明'
}

# ---------- E05 Git の名前とメール ----------

Test-Case -Name 'Get-GitGlobalConfig：設定がない（終了コード 1）なら空文字' -Covers 'D11-T' -Body {
    Reset-Fake
    Set-FakeTool -Pattern 'git config --global --get user.name' -ExitCode 1
    Assert-Equal -Expected '' -Actual (Get-GitGlobalConfig -Key 'user.name')
}

Test-Case -Name 'Get-GitGlobalConfig：前後の空白を取る' -Covers 'D11-F' -Body {
    Reset-Fake
    Set-FakeTool -Pattern 'git config --global --get user.name' -ExitCode 0 -Output " Arai `n"
    Assert-Equal -Expected 'Arai' -Actual (Get-GitGlobalConfig -Key 'user.name')
}

Test-Case -Name 'E05：名前あり・メールあり → OK' -Covers 'D12-TT' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    Assert-Result (Test-GitIdentity) 'E05' 'OK'
}

Test-Case -Name 'E05：名前あり・メールなし → NG（user.email だけ）' -Covers 'D12-TF', 'D13-F', 'D14-T' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    Set-FakeTool -Pattern 'git config --global --get user.email' -ExitCode 1
    $r = Test-GitIdentity
    Assert-Result $r 'E05' 'NG'
    Assert-Equal -Expected 'ありません：user.email' -Actual $r.Detail
}

Test-Case -Name 'E05：名前なし・メールあり → NG（user.name だけ）' -Covers 'D12-FT', 'D13-T', 'D14-F' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    Set-FakeTool -Pattern 'git config --global --get user.name' -ExitCode 1
    $r = Test-GitIdentity
    Assert-Result $r 'E05' 'NG'
    Assert-Equal -Expected 'ありません：user.name' -Actual $r.Detail
}

Test-Case -Name 'E05：名前なし・メールなし → NG（両方）' -Covers 'D12-FF', 'D13-T', 'D14-T' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    Set-FakeTool -Pattern 'git config --global --get user.name' -ExitCode 1
    Set-FakeTool -Pattern 'git config --global --get user.email' -ExitCode 1
    Assert-Equal -Expected 'ありません：user.name、user.email' -Actual (Test-GitIdentity).Detail
}

# ---------- E06 Git の改行・表示設定 ----------

Test-Case -Name 'E06：autocrlf=false・quotepath=false → OK' -Covers 'D15-TT' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    Assert-Result (Test-GitSettings) 'E06' 'OK'
}

Test-Case -Name 'E06：autocrlf=false・quotepath=true → 注意' -Covers 'D15-TF', 'D16-F' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    Set-FakeTool -Pattern 'git config --global --get core.quotepath' -ExitCode 0 -Output 'true'
    Assert-Result (Test-GitSettings) 'E06' 'WARN'
}

Test-Case -Name 'E06：autocrlf=true・quotepath=false → NG' -Covers 'D15-FT', 'D16-T' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    Set-FakeTool -Pattern 'git config --global --get core.autocrlf' -ExitCode 0 -Output 'true'
    Assert-Result (Test-GitSettings) 'E06' 'NG'
}

Test-Case -Name 'E06：autocrlf 未設定・quotepath 未設定 → NG' -Covers 'D15-FF', 'D16-T' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    Set-FakeTool -Pattern 'git config --global --get core.autocrlf' -ExitCode 1
    Set-FakeTool -Pattern 'git config --global --get core.quotepath' -ExitCode 1
    Assert-Result (Test-GitSettings) 'E06' 'NG'
}

# ---------- E07 MySQL の版 ----------

Test-Case -Name 'E07：8.4.5 なら OK' -Covers 'D17-F', 'D18-T', 'D19-T' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    Assert-Result (Test-MySqlVersion -Settings $S) 'E07' 'OK'
}

Test-Case -Name 'E07：mysql.exe がなければ NG' -Covers 'D17-T' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    $script:Fake.Files.Remove($S.MySqlExe)
    Assert-Result (Test-MySqlVersion -Settings $S) 'E07' 'NG'
}

Test-Case -Name 'E07：8.4.6 なら注意' -Covers 'D17-F', 'D18-T', 'D19-F', 'D20-T' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    Set-FakeTool -Pattern "$($S.MySqlExe) --version" -ExitCode 0 -Output 'mysql  Ver 8.4.6 for Win64'
    Assert-Result (Test-MySqlVersion -Settings $S) 'E07' 'WARN'
}

Test-Case -Name 'E07：8.0.40 なら NG' -Covers 'D17-F', 'D18-T', 'D19-F', 'D20-F' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    Set-FakeTool -Pattern "$($S.MySqlExe) --version" -ExitCode 0 -Output 'mysql  Ver 8.0.40 for Win64'
    Assert-Result (Test-MySqlVersion -Settings $S) 'E07' 'NG'
}

Test-Case -Name 'E07：版が読めなければ NG（不明）' -Covers 'D17-F', 'D18-F', 'D19-F', 'D20-F' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    Set-FakeTool -Pattern "$($S.MySqlExe) --version" -ExitCode 1 -Output 'error'
    $r = Test-MySqlVersion -Settings $S
    Assert-Result $r 'E07' 'NG'
    Assert-Match -Text $r.Detail -Pattern '不明'
}

# ---------- E08 MySQL のサービス ----------

Test-Case -Name 'E08：Running なら OK' -Covers 'D21-F', 'D22-T' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    Assert-Result (Test-MySqlService -Settings $S) 'E08' 'OK'
}

Test-Case -Name 'E08：サービスがなければ NG' -Covers 'D21-T' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    $script:Fake.Services.Remove($S.MySqlService)
    Assert-Result (Test-MySqlService -Settings $S) 'E08' 'NG'
}

Test-Case -Name 'E08：Stopped なら NG' -Covers 'D21-F', 'D22-F' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    $script:Fake.Services[$S.MySqlService] = 'Stopped'
    $r = Test-MySqlService -Settings $S
    Assert-Result $r 'E08' 'NG'
    Assert-Match -Text $r.Fix -Pattern 'Start-Service'
}

# ---------- E09 DB_PASSWORD ----------

Test-Case -Name 'E09：あれば OK（値は表示しない）' -Covers 'D23-F' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    $r = Test-DbPassword
    Assert-Result $r 'E09' 'OK'
    Assert-True -Condition (-not $r.Detail.Contains('pass'))
}

Test-Case -Name 'E09：なければ NG' -Covers 'D23-T' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    $script:Fake.Env.Remove('DB_PASSWORD')
    Assert-Result (Test-DbPassword) 'E09' 'NG'
}

# ---------- E10・E11 DB ----------

Test-Case -Name 'Invoke-MySqlQuery：パスワードは一時ファイルで渡し、引数に出さず、あとで消す' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    $null = Invoke-MySqlQuery -Settings $S -Sql 'SHOW GRANTS'
    $call = $script:Fake.Calls[$script:Fake.Calls.Count - 1]
    Assert-True -Condition (-not $call.Contains('pass"word')) -Message '引数にパスワードが出ている'
    $saved = $script:Fake.OptionFileTexts[0]
    Assert-Equal -Expected "[client]`npassword=`"pass\`"word\\1`"`n" -Actual $saved.Text
    Assert-True -Condition (-not (Test-Path -LiteralPath $saved.Path)) -Message '一時ファイルが消えていない'
    Assert-Match -Text $call -Pattern '-u shift_app -h localhost -N -B -e SHOW GRANTS$'
}

Test-Case -Name 'E10：2つの DB があれば OK' -Covers 'D24-F', 'D25-F', 'D26-T' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    Assert-Result (Test-DbConnection -Settings $S) 'E10' 'OK'
    Assert-Match -Text $script:Fake.Calls[$script:Fake.Calls.Count - 1] -Pattern "IN \('shift_management','shift_test'\)"
}

Test-Case -Name 'E10：接続できなければ NG' -Covers 'D24-T' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    Set-FakeTool -Pattern "$($S.MySqlExe) --defaults-extra-file=* SELECT SCHEMA_NAME *" -ExitCode 1 -Output "ERROR 1045 (28000): Access denied for user 'shift_app'@'localhost'"
    $r = Test-DbConnection -Settings $S
    Assert-Result $r 'E10' 'NG'
    Assert-Match -Text $r.Detail -Pattern 'Access denied'
}

Test-Case -Name 'E10：テスト用 DB がなければ NG' -Covers 'D24-F', 'D25-T', 'D25-F', 'D26-F' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    Set-FakeTool -Pattern "$($S.MySqlExe) --defaults-extra-file=* SELECT SCHEMA_NAME *" -ExitCode 0 -Output "shift_management`r"
    $r = Test-DbConnection -Settings $S
    Assert-Result $r 'E10' 'NG'
    Assert-Equal -Expected 'DB がありません：shift_test' -Actual $r.Detail
}

Test-Case -Name 'E10：DB が1つもなければ NG（2つとも出す）' -Covers 'D24-F', 'D25-T', 'D26-F' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    Set-FakeTool -Pattern "$($S.MySqlExe) --defaults-extra-file=* SELECT SCHEMA_NAME *" -ExitCode 0 -Output ''
    Assert-Equal -Expected 'DB がありません：shift_management、shift_test' -Actual (Test-DbConnection -Settings $S).Detail
}

Test-Case -Name 'E11：成功・SHOW_ROUTINE あり → OK' -Covers 'D27-TT' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    Assert-Result (Test-DbBackupGrant -Settings $S) 'E11' 'OK'
}

Test-Case -Name 'E11：成功・SHOW_ROUTINE なし → 注意' -Covers 'D27-TF' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    Set-FakeTool -Pattern "$($S.MySqlExe) --defaults-extra-file=* SHOW GRANTS" -ExitCode 0 -Output 'GRANT USAGE ON *.* TO shift_app'
    Assert-Result (Test-DbBackupGrant -Settings $S) 'E11' 'WARN'
}

Test-Case -Name 'E11：失敗・出力に SHOW_ROUTINE の文字あり → 注意' -Covers 'D27-FT' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    Set-FakeTool -Pattern "$($S.MySqlExe) --defaults-extra-file=* SHOW GRANTS" -ExitCode 1 -Output 'ERROR near SHOW_ROUTINE'
    Assert-Result (Test-DbBackupGrant -Settings $S) 'E11' 'WARN'
}

Test-Case -Name 'E11：失敗・SHOW_ROUTINE なし → 注意' -Covers 'D27-FF' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    Set-FakeTool -Pattern "$($S.MySqlExe) --defaults-extra-file=* SHOW GRANTS" -ExitCode 1 -Output 'ERROR 2003'
    Assert-Result (Test-DbBackupGrant -Settings $S) 'E11' 'WARN'
}

# ---------- E12 Eclipse と Lombok ----------

Test-Case -Name 'E12：eclipse.exe あり・ini あり・Lombok あり → OK' -Covers 'D28-F', 'D29-TT' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    Assert-Result (Test-Eclipse -Settings $S) 'E12' 'OK'
}

Test-Case -Name 'E12：eclipse.exe なし → NG' -Covers 'D28-T' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    $script:Fake.Files.Remove([System.IO.Path]::Combine($S.EclipseDir, 'eclipse.exe'))
    Assert-Result (Test-Eclipse -Settings $S) 'E12' 'NG'
}

Test-Case -Name 'E12：ini あり・Lombok なし → NG' -Covers 'D28-F', 'D29-TF' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    $script:Fake.FileText[[System.IO.Path]::Combine($S.EclipseDir, 'eclipse.ini')] = "-vmargs`n-Xmx2g"
    Assert-Result (Test-Eclipse -Settings $S) 'E12' 'NG'
}

Test-Case -Name 'E12：ini なし（中身は Lombok あり）→ NG（短絡評価で中身は読まない）' -Covers 'D28-F', 'D29-FT' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    $script:Fake.Files.Remove([System.IO.Path]::Combine($S.EclipseDir, 'eclipse.ini'))
    Assert-Result (Test-Eclipse -Settings $S) 'E12' 'NG'
}

Test-Case -Name 'E12：ini なし・中身も Lombok なし → NG' -Covers 'D28-F', 'D29-FF' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    $script:Fake.Files.Remove([System.IO.Path]::Combine($S.EclipseDir, 'eclipse.ini'))
    $script:Fake.FileText[[System.IO.Path]::Combine($S.EclipseDir, 'eclipse.ini')] = '-Xmx2g'
    Assert-Result (Test-Eclipse -Settings $S) 'E12' 'NG'
}

# ---------- E13〜E15 ----------

Test-Case -Name 'E13：8080 が空いていれば OK' -Covers 'D30-F' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    Assert-Result (Test-AppPort -Settings $S) 'E13' 'OK'
}

Test-Case -Name 'E13：8080 が使われていれば注意' -Covers 'D30-T' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    $script:Fake.Ports[$S.AppPort] = $true
    Assert-Result (Test-AppPort -Settings $S) 'E13' 'WARN'
}

Test-Case -Name 'E14：保存先があれば OK' -Covers 'D31-T' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    Assert-Result (Test-BackupDir -Settings $S) 'E14' 'OK'
}

Test-Case -Name 'E14：保存先がなければ NG' -Covers 'D31-F' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    $script:Fake.Files.Remove($S.BackupDir)
    Assert-Result (Test-BackupDir -Settings $S) 'E14' 'NG'
}

Test-Case -Name 'E15：2つとも届けば OK' -Covers 'D32-F', 'D33-T' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    Assert-Result (Test-Network -Settings $S) 'E15' 'OK'
}

Test-Case -Name 'E15：Maven Central に届かなければ NG（届かない方だけ出す）' -Covers 'D32-T', 'D32-F', 'D33-F' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    $script:Fake.Urls[$S.Urls[1]] = $false
    $r = Test-Network -Settings $S
    Assert-Result $r 'E15' 'NG'
    Assert-Equal -Expected ('届きません：' + $S.Urls[1]) -Actual $r.Detail
}

# ---------- E01〜E15 まとめ（DB を確かめるかの判定：MySQL の版が NG でない・サービス OK・パスワード OK） ----------

# a：MySQL の版が NG でない、b：サービスが OK、c：DB_PASSWORD が OK
$dbCombos = @(
    @{ Id = 'D34-TTT'; Version = $true; Service = $true; Password = $true; Expect = 'OK' },
    @{ Id = 'D34-TTF'; Version = $true; Service = $true; Password = $false; Expect = 'SKIP' },
    @{ Id = 'D34-TFT'; Version = $true; Service = $false; Password = $true; Expect = 'SKIP' },
    @{ Id = 'D34-TFF'; Version = $true; Service = $false; Password = $false; Expect = 'SKIP' },
    @{ Id = 'D34-FTT'; Version = $false; Service = $true; Password = $true; Expect = 'SKIP' },
    @{ Id = 'D34-FTF'; Version = $false; Service = $true; Password = $false; Expect = 'SKIP' },
    @{ Id = 'D34-FFT'; Version = $false; Service = $false; Password = $true; Expect = 'SKIP' },
    @{ Id = 'D34-FFF'; Version = $false; Service = $false; Password = $false; Expect = 'SKIP' }
)
foreach ($combo in $dbCombos) {
    Test-Case -Name "まとめ（$($combo.Id)）：DB の確認は $($combo.Expect)" -Covers $combo.Id -Data $combo -Body {
        param($combo)
        Set-FakeHealthyEnvironment -Settings $S
        if (-not $combo.Version) {
            $script:Fake.Files.Remove($S.MySqlExe)
        }
        if (-not $combo.Service) {
            $script:Fake.Services[$S.MySqlService] = 'Stopped'
        }
        if (-not $combo.Password) {
            $script:Fake.Env.Remove('DB_PASSWORD')
        }
        $results = @(Invoke-EnvironmentCheck -Settings $S)
        Assert-Equal -Expected 'E01,E02,E03,E04,E05,E06,E07,E08,E09,E10,E11,E12,E13,E14,E15' -Actual (($results | ForEach-Object { $_.Id }) -join ',')
        Assert-Equal -Expected $combo.Expect -Actual $results[9].Status -Message 'E10'
        Assert-Equal -Expected $combo.Expect -Actual $results[10].Status -Message 'E11'
    }
}

Test-Case -Name 'まとめ：MySQL の版が注意（8.4.6）でも DB は確かめる' -Body {
    Set-FakeHealthyEnvironment -Settings $S
    Set-FakeTool -Pattern "$($S.MySqlExe) --version" -ExitCode 0 -Output 'Ver 8.4.6'
    $results = @(Invoke-EnvironmentCheck -Settings $S)
    Assert-Equal -Expected 'WARN' -Actual $results[6].Status
    Assert-Equal -Expected 'OK' -Actual $results[9].Status
}

# ---------- R01 リポジトリのフォルダ ----------

Test-Case -Name 'R01：フォルダなし → NG' -Covers 'D35-T' -Body {
    Set-FakeHealthyRepository -Settings $S -RepoPath $Repo
    $script:Fake.Files.Remove($Repo)
    Assert-Result (Test-RepoFolder -RepoPath $Repo) 'R01' 'NG'
}

Test-Case -Name 'R01：成功・true → OK' -Covers 'D35-F', 'D36-TT' -Body {
    Set-FakeHealthyRepository -Settings $S -RepoPath $Repo
    Assert-Result (Test-RepoFolder -RepoPath $Repo) 'R01' 'OK'
}

Test-Case -Name 'R01：成功・false（.git の中など）→ NG' -Covers 'D35-F', 'D36-TF' -Body {
    Set-FakeHealthyRepository -Settings $S -RepoPath $Repo
    Set-FakeTool -Pattern "git -C $Repo rev-parse --is-inside-work-tree" -ExitCode 0 -Output 'false'
    Assert-Result (Test-RepoFolder -RepoPath $Repo) 'R01' 'NG'
}

Test-Case -Name 'R01：失敗・出力 true → NG' -Covers 'D35-F', 'D36-FT' -Body {
    Set-FakeHealthyRepository -Settings $S -RepoPath $Repo
    Set-FakeTool -Pattern "git -C $Repo rev-parse --is-inside-work-tree" -ExitCode 128 -Output 'true'
    Assert-Result (Test-RepoFolder -RepoPath $Repo) 'R01' 'NG'
}

Test-Case -Name 'R01：失敗・not a git repository → NG' -Covers 'D35-F', 'D36-FF' -Body {
    Set-FakeHealthyRepository -Settings $S -RepoPath $Repo
    Set-FakeTool -Pattern "git -C $Repo rev-parse --is-inside-work-tree" -ExitCode 128 -Output 'fatal: not a git repository'
    Assert-Result (Test-RepoFolder -RepoPath $Repo) 'R01' 'NG'
}

# ---------- R02 リモート ----------

Test-Case -Name 'URL の正規化：末尾の / を取る' -Covers 'D37-T', 'D38-F' -Body {
    Assert-Equal -Expected 'https://github.com/a/b' -Actual (ConvertTo-NormalizedRepoUrl -Url 'https://github.com/a/b/')
}

Test-Case -Name 'URL の正規化：大文字と末尾の .git' -Covers 'D37-F', 'D38-T' -Body {
    Assert-Equal -Expected 'https://github.com/a/b' -Actual (ConvertTo-NormalizedRepoUrl -Url " https://GitHub.com/A/B.git`n")
}

Test-Case -Name 'URL の正規化：.git/ の両方' -Covers 'D37-T', 'D38-T' -Body {
    Assert-Equal -Expected 'https://github.com/a/b' -Actual (ConvertTo-NormalizedRepoUrl -Url 'https://github.com/a/b.git/')
}

Test-Case -Name 'URL の正規化：どちらもなし' -Covers 'D37-F', 'D38-F' -Body {
    Assert-Equal -Expected 'https://github.com/a/b' -Actual (ConvertTo-NormalizedRepoUrl -Url 'https://github.com/a/b')
}

Test-Case -Name 'R02：成功・一致（大文字小文字違いの URL）→ OK' -Covers 'D39-TT' -Body {
    Set-FakeHealthyRepository -Settings $S -RepoPath $Repo
    Set-FakeTool -Pattern "git -C $Repo remote get-url origin" -ExitCode 0 -Output 'https://github.com/amasato1221/Numajo_Shift_Management_System_Fgroup.git'
    Assert-Result (Test-RepoRemote -RepoPath $Repo -Settings $S) 'R02' 'OK'
}

Test-Case -Name 'R02：成功・別のリポジトリ（フォーク）→ NG' -Covers 'D39-TF' -Body {
    Set-FakeHealthyRepository -Settings $S -RepoPath $Repo
    Set-FakeTool -Pattern "git -C $Repo remote get-url origin" -ExitCode 0 -Output 'https://github.com/kawabata/numajo_shift_management_system_fgroup.git'
    $r = Test-RepoRemote -RepoPath $Repo -Settings $S
    Assert-Result $r 'R02' 'NG'
    Assert-Match -Text $r.Fix -Pattern 'set-url'
}

Test-Case -Name 'R02：失敗・出力は正しい URL → NG' -Covers 'D39-FT' -Body {
    Set-FakeHealthyRepository -Settings $S -RepoPath $Repo
    Set-FakeTool -Pattern "git -C $Repo remote get-url origin" -ExitCode 2 -Output $S.RepoUrl
    Assert-Result (Test-RepoRemote -RepoPath $Repo -Settings $S) 'R02' 'NG'
}

Test-Case -Name 'R02：失敗・origin がない → NG' -Covers 'D39-FF' -Body {
    Set-FakeHealthyRepository -Settings $S -RepoPath $Repo
    Set-FakeTool -Pattern "git -C $Repo remote get-url origin" -ExitCode 2 -Output "error: No such remote 'origin'"
    Assert-Result (Test-RepoRemote -RepoPath $Repo -Settings $S) 'R02' 'NG'
}

# ---------- R03 fetch ----------

Test-Case -Name 'R03：成功 → OK' -Covers 'D40-T' -Body {
    Set-FakeHealthyRepository -Settings $S -RepoPath $Repo
    Assert-Result (Test-RepoFetch -RepoPath $Repo) 'R03' 'OK'
}

Test-Case -Name 'R03：失敗 → NG' -Covers 'D40-F' -Body {
    Set-FakeHealthyRepository -Settings $S -RepoPath $Repo
    Set-FakeTool -Pattern "git -C $Repo fetch origin --prune" -ExitCode 128 -Output 'fatal: unable to access'
    Assert-Result (Test-RepoFetch -RepoPath $Repo) 'R03' 'NG'
}

# ---------- R04 main ----------
# 判定 D41：(a) 終了コードが 0 でない、(b) 出力が「数字 空白 数字」でない

Test-Case -Name 'R04：失敗・形も違う → NG' -Covers 'D41-TT' -Body {
    Set-FakeHealthyRepository -Settings $S -RepoPath $Repo
    Set-FakeTool -Pattern "git -C $Repo rev-list --left-right --count main...origin/main" -ExitCode 128 -Output "fatal: ambiguous argument 'main...origin/main'"
    Assert-Result (Test-RepoMainSync -RepoPath $Repo) 'R04' 'NG'
}

Test-Case -Name 'R04：失敗・形は正しい → NG' -Covers 'D41-TF' -Body {
    Set-FakeHealthyRepository -Settings $S -RepoPath $Repo
    Set-FakeTool -Pattern "git -C $Repo rev-list --left-right --count main...origin/main" -ExitCode 1 -Output "0`t0"
    Assert-Result (Test-RepoMainSync -RepoPath $Repo) 'R04' 'NG'
}

Test-Case -Name 'R04：成功・形が違う → NG' -Covers 'D41-FT' -Body {
    Set-FakeHealthyRepository -Settings $S -RepoPath $Repo
    Set-FakeTool -Pattern "git -C $Repo rev-list --left-right --count main...origin/main" -ExitCode 0 -Output 'abc'
    Assert-Result (Test-RepoMainSync -RepoPath $Repo) 'R04' 'NG'
}

Test-Case -Name 'R04：先 0・遅れ 0 → OK' -Covers 'D41-FF', 'D42-F', 'D43-F' -Body {
    Set-FakeHealthyRepository -Settings $S -RepoPath $Repo
    Assert-Result (Test-RepoMainSync -RepoPath $Repo) 'R04' 'OK'
}

Test-Case -Name 'R04：先 0・遅れ 3 → 注意' -Covers 'D41-FF', 'D42-F', 'D43-T' -Body {
    Set-FakeHealthyRepository -Settings $S -RepoPath $Repo
    Set-FakeTool -Pattern "git -C $Repo rev-list --left-right --count main...origin/main" -ExitCode 0 -Output "0`t3"
    $r = Test-RepoMainSync -RepoPath $Repo
    Assert-Result $r 'R04' 'WARN'
    Assert-Match -Text $r.Detail -Pattern '3 件'
}

Test-Case -Name 'R04：先 2・遅れ 0 → NG（main に直接コミット）' -Covers 'D41-FF', 'D42-T' -Body {
    Set-FakeHealthyRepository -Settings $S -RepoPath $Repo
    Set-FakeTool -Pattern "git -C $Repo rev-list --left-right --count main...origin/main" -ExitCode 0 -Output "2`t0"
    Assert-Result (Test-RepoMainSync -RepoPath $Repo) 'R04' 'NG'
}

Test-Case -Name 'R04：先 1・遅れ 4 → NG（先を優先）' -Covers 'D41-FF', 'D42-T' -Body {
    Set-FakeHealthyRepository -Settings $S -RepoPath $Repo
    Set-FakeTool -Pattern "git -C $Repo rev-list --left-right --count main...origin/main" -ExitCode 0 -Output "1`t4"
    Assert-Result (Test-RepoMainSync -RepoPath $Repo) 'R04' 'NG'
}

# ---------- R05 プッシュ ----------

Test-Case -Name 'ブランチ名の文字：user.name から英小文字・数字・ハイフンだけ' -Covers 'D44-F' -Body {
    Reset-Fake
    Set-FakeTool -Pattern 'git config --global --get user.name' -ExitCode 0 -Output 'Kurihara Taro-2'
    Assert-Equal -Expected 'kuriharataro-2' -Actual (Get-BranchTag)
}

Test-Case -Name 'ブランチ名の文字：user.name が日本語なら USERNAME を使う' -Covers 'D44-T', 'D45-F' -Body {
    Reset-Fake
    Set-FakeTool -Pattern 'git config --global --get user.name' -ExitCode 0 -Output '川端'
    $script:Fake.Env['USERNAME'] = 'Kawabata'
    Assert-Equal -Expected 'kawabata' -Actual (Get-BranchTag)
}

Test-Case -Name 'ブランチ名の文字：どちらも使えなければ user' -Covers 'D44-T', 'D45-T' -Body {
    Reset-Fake
    Set-FakeTool -Pattern 'git config --global --get user.name' -ExitCode 1
    Assert-Equal -Expected 'user' -Actual (Get-BranchTag)
}

Test-Case -Name 'R05：-SkipPush なら未確認（プッシュしない）' -Covers 'D46-T' -Body {
    Set-FakeHealthyRepository -Settings $S -RepoPath $Repo
    Assert-Result (Test-RepoPush -RepoPath $Repo -SkipPush) 'R05' 'SKIP'
    Assert-Equal -Expected 0 -Actual @($script:Fake.Calls | Where-Object { $_ -like '*push*' }).Count
}

Test-Case -Name 'R05：作って消せれば OK（main と同じ場所の一時ブランチ）' -Covers 'D46-F', 'D47-F', 'D49-T' -Body {
    Set-FakeHealthyRepository -Settings $S -RepoPath $Repo
    Assert-Result (Test-RepoPush -RepoPath $Repo) 'R05' 'OK'
    $pushes = @($script:Fake.Calls | Where-Object { $_ -like '*push*' })
    Assert-Equal -Expected "git -C $Repo push origin refs/remotes/origin/main:refs/heads/test/env-check-kurihara-20261022093000" -Actual $pushes[0]
    Assert-Equal -Expected "git -C $Repo push origin --delete test/env-check-kurihara-20261022093000" -Actual $pushes[1]
}

Test-Case -Name 'R05：権限なし（403）→ NG・消す操作はしない' -Covers 'D46-F', 'D47-T', 'D48-T' -Body {
    Set-FakeHealthyRepository -Settings $S -RepoPath $Repo
    Set-FakeTool -Pattern "git -C $Repo push origin refs/remotes/origin/main:refs/heads/*" -ExitCode 128 -Output 'remote: Permission to amasato1221/x.git denied to kawabata. fatal: ... returned error: 403'
    $r = Test-RepoPush -RepoPath $Repo
    Assert-Result $r 'R05' 'NG'
    Assert-Equal -Expected '書き込みの権限がありません' -Actual $r.Detail
    Assert-Equal -Expected 0 -Actual @($script:Fake.Calls | Where-Object { $_ -like '*--delete*' }).Count
}

Test-Case -Name 'R05：ほかの理由で失敗 → NG（出力をそのまま出す）' -Covers 'D46-F', 'D47-T', 'D48-F' -Body {
    Set-FakeHealthyRepository -Settings $S -RepoPath $Repo
    Set-FakeTool -Pattern "git -C $Repo push origin refs/remotes/origin/main:refs/heads/*" -ExitCode 128 -Output 'fatal: unable to access: Could not resolve host'
    $r = Test-RepoPush -RepoPath $Repo
    Assert-Result $r 'R05' 'NG'
    Assert-Match -Text $r.Detail -Pattern 'Could not resolve host'
}

Test-Case -Name 'R05：作れたが消せない → 注意' -Covers 'D46-F', 'D47-F', 'D49-F' -Body {
    Set-FakeHealthyRepository -Settings $S -RepoPath $Repo
    Set-FakeTool -Pattern "git -C $Repo push origin --delete *" -ExitCode 1 -Output 'error'
    Assert-Result (Test-RepoPush -RepoPath $Repo) 'R05' 'WARN'
}

# ---------- R06 ロゴ（a：除外されていない、b：手元にある） ----------

Test-Case -Name 'R06：除外あり・ロゴあり → OK' -Covers 'D50-F', 'D51-T' -Body {
    Set-FakeHealthyRepository -Settings $S -RepoPath $Repo
    Assert-Result (Test-RepoLogo -RepoPath $Repo -Settings $S) 'R06' 'OK'
}

Test-Case -Name 'R06：除外あり・ロゴなし → 注意' -Covers 'D50-F', 'D51-F' -Body {
    Set-FakeHealthyRepository -Settings $S -RepoPath $Repo
    $script:Fake.Files.Remove([System.IO.Path]::Combine($Repo, $S.LogoPath))
    Assert-Result (Test-RepoLogo -RepoPath $Repo -Settings $S) 'R06' 'WARN'
}

Test-Case -Name 'R06：除外なし・ロゴあり → NG' -Covers 'D50-T' -Body {
    Set-FakeHealthyRepository -Settings $S -RepoPath $Repo
    Set-FakeTool -Pattern "git -C $Repo check-ignore -q $($S.LogoPath)" -ExitCode 1
    Assert-Result (Test-RepoLogo -RepoPath $Repo -Settings $S) 'R06' 'NG'
}

Test-Case -Name 'R06：除外なし・ロゴなし → NG' -Covers 'D50-T' -Body {
    Set-FakeHealthyRepository -Settings $S -RepoPath $Repo
    Set-FakeTool -Pattern "git -C $Repo check-ignore -q $($S.LogoPath)" -ExitCode 1
    $script:Fake.Files.Remove([System.IO.Path]::Combine($Repo, $S.LogoPath))
    Assert-Result (Test-RepoLogo -RepoPath $Repo -Settings $S) 'R06' 'NG'
}

# ---------- R01〜R06 まとめ ----------

Test-Case -Name 'まとめ：全部 OK' -Covers 'D52-F', 'D53-T' -Body {
    Set-FakeHealthyRepository -Settings $S -RepoPath $Repo
    $results = @(Invoke-RepositoryCheck -RepoPath $Repo -Settings $S)
    Assert-Equal -Expected 'R01:OK,R02:OK,R03:OK,R04:OK,R05:OK,R06:OK' -Actual (($results | ForEach-Object { $_.Id + ':' + $_.Status }) -join ',')
}

Test-Case -Name 'まとめ：-SkipPush を渡す' -Covers 'D52-F', 'D53-T' -Body {
    Set-FakeHealthyRepository -Settings $S -RepoPath $Repo
    $results = @(Invoke-RepositoryCheck -RepoPath $Repo -Settings $S -SkipPush)
    Assert-Equal -Expected 'SKIP' -Actual $results[4].Status
}

Test-Case -Name 'まとめ：R01 が NG なら残りは未確認' -Covers 'D52-T' -Body {
    Set-FakeHealthyRepository -Settings $S -RepoPath $Repo
    $script:Fake.Files.Remove($Repo)
    $results = @(Invoke-RepositoryCheck -RepoPath $Repo -Settings $S)
    Assert-Equal -Expected 'R01:NG,R02:SKIP,R03:SKIP,R04:SKIP,R05:SKIP,R06:SKIP' -Actual (($results | ForEach-Object { $_.Id + ':' + $_.Status }) -join ',')
}

Test-Case -Name 'まとめ：R03 が NG なら R04・R05 は未確認、R06 は確かめる' -Covers 'D52-F', 'D53-F' -Body {
    Set-FakeHealthyRepository -Settings $S -RepoPath $Repo
    Set-FakeTool -Pattern "git -C $Repo fetch origin --prune" -ExitCode 128
    $results = @(Invoke-RepositoryCheck -RepoPath $Repo -Settings $S)
    Assert-Equal -Expected 'R01:OK,R02:OK,R03:NG,R04:SKIP,R05:SKIP,R06:OK' -Actual (($results | ForEach-Object { $_.Id + ':' + $_.Status }) -join ',')
}

# ---------- 結果の表示 ----------

Test-Case -Name '表示の文字：OK・注意・NG・未確認' -Covers 'D54-OK', 'D54-WARN', 'D54-NG', 'D54-OTHER' -Body {
    Assert-Equal -Expected 'OK' -Actual (Get-StatusLabel -Status 'OK')
    Assert-Equal -Expected '注意' -Actual (Get-StatusLabel -Status 'WARN')
    Assert-Equal -Expected 'NG' -Actual (Get-StatusLabel -Status 'NG')
    Assert-Equal -Expected '未確認' -Actual (Get-StatusLabel -Status 'SKIP')
}

Test-Case -Name '表示：結果0件なら合格（繰り返しを1回も通らない）' -Covers 'D55-F', 'D58-TT', 'D60-F' -Body {
    $lines = @(Format-CheckReport -Title 't' -Results @())
    Assert-Equal -Expected 4 -Actual $lines.Count
    Assert-Equal -Expected '合計：OK 0 件、注意 0 件、NG 0 件、未確認 0 件' -Actual $lines[2]
    Assert-Equal -Expected '結果：合格です。' -Actual $lines[3]
    Assert-Equal -Expected 0 -Actual (Get-CheckExitCode -Results @())
}

# D56：(a) OK でない、(b) 直し方がある。D59：(a) NG、(b) SKIP
Test-Case -Name '表示：直し方は OK でなく直し方があるときだけ出す・注意だけなら合格' -Covers 'D55-T', 'D56-TT', 'D56-TF', 'D56-FT', 'D56-FF', 'D57', 'D58-TT', 'D59-FF', 'D60-F' -Body {
    $results = @(
        (New-CheckResult -Id 'A' -Name 'a' -Status 'WARN' -Detail 'd' -Fix '直す'),
        (New-CheckResult -Id 'B' -Name 'b' -Status 'WARN' -Detail 'd'),
        (New-CheckResult -Id 'C' -Name 'c' -Status 'OK' -Detail 'd' -Fix 'ここは出ない'),
        (New-CheckResult -Id 'D' -Name 'd' -Status 'OK' -Detail 'd')
    )
    $lines = @(Format-CheckReport -Title 't' -Results $results)
    Assert-Equal -Expected '===== t =====' -Actual $lines[0]
    Assert-Equal -Expected '[注意] A a：d' -Actual $lines[2]
    Assert-Equal -Expected '      → 直す' -Actual $lines[3]
    Assert-Equal -Expected '[注意] B b：d' -Actual $lines[4]
    Assert-Equal -Expected '[OK] C c：d' -Actual $lines[5]
    Assert-Equal -Expected '[OK] D d：d' -Actual $lines[6]
    Assert-Equal -Expected '合計：OK 2 件、注意 2 件、NG 0 件、未確認 0 件' -Actual $lines[7]
    Assert-Equal -Expected '結果：合格です。' -Actual $lines[8]
    Assert-Equal -Expected 0 -Actual (Get-CheckExitCode -Results $results)
}

Test-Case -Name '表示：NG あり・未確認なし → 不合格・終了コード 1' -Covers 'D58-FT', 'D59-TF', 'D60-T' -Body {
    $results = @((New-CheckResult -Id 'A' -Name 'a' -Status 'NG' -Detail 'd' -Fix 'f'))
    $lines = @(Format-CheckReport -Title 't' -Results $results)
    Assert-Match -Text $lines[-1] -Pattern '不合格'
    Assert-Equal -Expected 1 -Actual (Get-CheckExitCode -Results $results)
}

Test-Case -Name '表示：NG なし・未確認あり → 不合格・終了コード 1' -Covers 'D58-TF', 'D59-FT', 'D60-T' -Body {
    $results = @((New-SkipResult -Id 'A' -Name 'a' -Reason 'r'))
    $lines = @(Format-CheckReport -Title 't' -Results $results)
    Assert-Match -Text $lines[-1] -Pattern '不合格'
    Assert-Equal -Expected 1 -Actual (Get-CheckExitCode -Results $results)
}

Test-Case -Name '表示：NG あり・未確認あり → 不合格' -Covers 'D58-FF' -Body {
    $results = @((New-CheckResult -Id 'A' -Name 'a' -Status 'NG' -Detail 'd'), (New-SkipResult -Id 'B' -Name 'b' -Reason 'r'))
    Assert-Match -Text (@(Format-CheckReport -Title 't' -Results $results))[-1] -Pattern '不合格'
    Assert-Equal -Expected 1 -Actual (Get-CheckExitCode -Results $results)
}
