# 外とのやりとりの関数と、実行用スクリプトのテスト（置き換えずに本物を動かす）
# Windows でだけ動く Get-OsInfo・Get-ServiceStatus・Test-PortListening は除く（README.md 4.3）。

. (Join-Path $LibDir 'EnvCheck.Lib.ps1')

Test-Case -Name 'Invoke-Tool：ないコマンドは終了コード -1' -Covers 'W1-T' -Body {
    $r = Invoke-Tool -FilePath 'no-such-command-envcheck' -Arguments @('x')
    Assert-Equal -Expected -1 -Actual $r.ExitCode
    Assert-Equal -Expected '' -Actual $r.Output
}

Test-Case -Name 'Invoke-Tool：あるコマンドは終了コードと出力を返す' -Covers 'W1-F' -Body {
    $r = Invoke-Tool -FilePath 'git' -Arguments @('--version')
    Assert-Equal -Expected 0 -Actual $r.ExitCode
    Assert-Match -Text $r.Output -Pattern '^git version '
}

Test-Case -Name 'Invoke-Tool：標準エラーも出力に入り、ErrorActionPreference を元に戻す' -Covers 'W1-F' -Body {
    $ErrorActionPreference = 'Stop'
    $r = Invoke-Tool -FilePath 'sh' -Arguments @('-c', 'echo out; echo err 1>&2; exit 3')
    Assert-Equal -Expected 3 -Actual $r.ExitCode
    # 標準出力と標準エラーの順番は OS が決めるので、両方が入っていることだけを見る
    Assert-Equal -Expected 'err,out' -Actual ((@($r.Output -split "`n") | Sort-Object) -join ',')
    Assert-Equal -Expected 'Stop' -Actual $ErrorActionPreference
}

Test-Case -Name 'Test-FileExists・Get-FileText：あるファイルと日本語' -Body {
    $path = [System.IO.Path]::GetTempFileName()
    try {
        [System.IO.File]::WriteAllText($path, "-javaagent:lombok.jar`n日本語", (New-Object System.Text.UTF8Encoding $false))
        Assert-True -Condition (Test-FileExists -Path $path)
        Assert-Equal -Expected "-javaagent:lombok.jar`n日本語" -Actual (Get-FileText -Path $path)
    } finally {
        Remove-Item -LiteralPath $path -Force
    }
    Assert-True -Condition (-not (Test-FileExists -Path $path))
}

Test-Case -Name 'Get-EnvValue：ある変数とない変数' -Body {
    [Environment]::SetEnvironmentVariable('ENVCHECK_TEST', 'abc')
    try {
        Assert-Equal -Expected 'abc' -Actual (Get-EnvValue -Name 'ENVCHECK_TEST')
    } finally {
        [Environment]::SetEnvironmentVariable('ENVCHECK_TEST', $null)
    }
    Assert-Null -Value (Get-EnvValue -Name 'ENVCHECK_TEST')
}

Test-Case -Name 'Get-Timestamp：14桁の数字' -Body {
    Assert-Match -Text (Get-Timestamp) -Pattern '^\d{14}$'
}

# HTTP サーバーを手元で動かして確かめる（200 は届く、404 も返事があるので届く、閉じたポートは届かない）
$server = Start-Process -FilePath 'python3' -ArgumentList @('-m', 'http.server', '18765', '--bind', '127.0.0.1', '--directory', $LibDir) -PassThru -RedirectStandardError (Join-Path ([System.IO.Path]::GetTempPath()) 'envcheck-http.log')
Start-Sleep -Seconds 2
try {
    Test-Case -Name 'Test-HttpsReachable：200 なら届く' -Covers 'W2-OK' -Body {
        Assert-True -Condition (Test-HttpsReachable -Url 'http://127.0.0.1:18765/')
    }
    Test-Case -Name 'Test-HttpsReachable：404 でも返事があれば届く' -Covers 'W2-T' -Body {
        Assert-True -Condition (Test-HttpsReachable -Url 'http://127.0.0.1:18765/no-such-file')
    }
    Test-Case -Name 'Test-HttpsReachable：閉じたポートは届かない' -Covers 'W2-F' -Body {
        Assert-True -Condition (-not (Test-HttpsReachable -Url 'http://127.0.0.1:18766/'))
    }
} finally {
    Stop-Process -Id $server.Id -Force
}

# 実行用スクリプト（このリポジトリで本物の git を使う。プッシュはしない）
$RepoRoot = (Resolve-Path (Join-Path $LibDir '..\..')).Path

Test-Case -Name 'check-repository.ps1：RepoPath を省くと2つ上のフォルダ・ファイルに保存しない' -Covers 'M3-T', 'M2-F' -Body {
    $output = & (Join-Path $LibDir 'check-repository.ps1') -SkipPush
    Assert-Equal -Expected 1 -Actual $LASTEXITCODE -Message 'SkipPush なので未確認があり 1'
    Assert-Match -Text ($output -join "`n") -Pattern ([regex]::Escape("[OK] R01 リポジトリのフォルダ：$RepoRoot"))
    Assert-Match -Text ($output -join "`n") -Pattern '\[未確認\] R05'
}

Test-Case -Name 'check-repository.ps1：RepoPath を指定・ファイルに保存する' -Covers 'M3-F', 'M2-T' -Body {
    $outFile = Join-Path ([System.IO.Path]::GetTempPath()) 'envcheck-repo-result.txt'
    $null = & (Join-Path $LibDir 'check-repository.ps1') -RepoPath '/no/such/repo' -OutFile $outFile
    Assert-Equal -Expected 1 -Actual $LASTEXITCODE
    $text = Get-Content -LiteralPath $outFile -Raw -Encoding UTF8
    Remove-Item -LiteralPath $outFile
    Assert-Match -Text $text -Pattern '\[NG\] R01 リポジトリのフォルダ：/no/such/repo がありません'
}

Test-Case -Name 'check-environment.ps1：ファイルに保存しない／保存する（Linux なので不合格になる）' -Covers 'M1-F', 'M1-T' -Body {
    # Linux にない Windows のコマンドを、空の結果を返すものに置き換える
    function global:Get-CimInstance { return [pscustomobject]@{ Caption = 'Linux（テスト）'; BuildNumber = 0 } }
    function global:Get-Service { return $null }
    function global:Get-NetTCPConnection { return $null }
    $outFile = Join-Path ([System.IO.Path]::GetTempPath()) 'envcheck-env-result.txt'
    $first = & (Join-Path $LibDir 'check-environment.ps1') 2>$null
    Assert-Equal -Expected 1 -Actual $LASTEXITCODE
    Assert-Match -Text ($first -join "`n") -Pattern '===== 開発環境の確認 ====='
    $null = & (Join-Path $LibDir 'check-environment.ps1') -OutFile $outFile 2>$null
    $text = Get-Content -LiteralPath $outFile -Raw -Encoding UTF8
    Remove-Item -LiteralPath $outFile
    Assert-Match -Text $text -Pattern '\[NG\] E07 MySQL の版'
    Assert-Match -Text $text -Pattern '\[注意\] E01 Windows の版：Linux（テスト）'
    Remove-Item -Path 'function:global:Get-CimInstance', 'function:global:Get-Service', 'function:global:Get-NetTCPConnection'
}
