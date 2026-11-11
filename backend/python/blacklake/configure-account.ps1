# Local interactive credential entry; no login/network request is made.
# -Name <name> saves a second account as account-<name>.dpapi (lowercase letters, digits, '-');
# without -Name it writes the original account.dpapi. The collector picks one via config.json "account".
param([string]$Name = '')
$ErrorActionPreference = 'Stop'
if ($Name -and $Name -cnotmatch '^[a-z0-9-]{1,32}$') { throw 'Name must be lowercase letters, digits or -' }
$secretFileName = if ($Name) { "account-$Name.dpapi" } else { 'account.dpapi' }
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Security
$form = New-Object System.Windows.Forms.Form
$form.Text = '黑湖只读联调：本机账号配置' + $(if ($Name) { '（' + $Name + '）' } else { '' })
$form.Width = 480
$form.Height = 330
$form.StartPosition = 'CenterScreen'
$form.FormBorderStyle = 'FixedDialog'
$form.MaximizeBox = $false
$description = New-Object System.Windows.Forms.Label
$description.Text = '仅保存到当前 Windows 用户加密文件；不会发送登录请求。填「手机号」，或填「工厂代码＋用户名」（OpenAPI type=1，不需要验证码）。'
$description.SetBounds(15, 15, 440, 35)
$phoneLabel = New-Object System.Windows.Forms.Label
$phoneLabel.Text = '手机号'
$phoneLabel.SetBounds(15, 60, 90, 24)
$phoneInput = New-Object System.Windows.Forms.TextBox
$phoneInput.SetBounds(110, 58, 320, 28)
$passwordLabel = New-Object System.Windows.Forms.Label
$passwordLabel.Text = '密码'
$passwordLabel.SetBounds(15, 180, 90, 24)
$passwordInput = New-Object System.Windows.Forms.TextBox
$passwordInput.UseSystemPasswordChar = $true
$passwordInput.SetBounds(110, 178, 320, 28)
$codeLabel = New-Object System.Windows.Forms.Label
$codeLabel.Text = '工厂代码'
$codeLabel.SetBounds(15, 100, 90, 24)
$codeInput = New-Object System.Windows.Forms.TextBox
$codeInput.SetBounds(110, 98, 320, 28)
$userLabel = New-Object System.Windows.Forms.Label
$userLabel.Text = '用户名'
$userLabel.SetBounds(15, 140, 90, 24)
$userInput = New-Object System.Windows.Forms.TextBox
$userInput.SetBounds(110, 138, 320, 28)
$save = New-Object System.Windows.Forms.Button
$save.Text = '加密保存'
$save.SetBounds(310, 225, 120, 32)
$save.Add_Click({
    $byName = $codeInput.Text.Trim() -and $userInput.Text.Trim()
    if ((-not $phoneInput.Text.Trim() -and -not $byName) -or -not $passwordInput.Text) { return }
    $secretDirectory = Join-Path $env:LOCALAPPDATA 'Cretas\Blacklake'
    [System.IO.Directory]::CreateDirectory($secretDirectory) | Out-Null
    $secretPath = Join-Path $secretDirectory $secretFileName
    $secretValue = @{ phone = $phoneInput.Text.Trim(); password = $passwordInput.Text }
    if ($byName) { $secretValue.code = $codeInput.Text.Trim(); $secretValue.username = $userInput.Text.Trim() }
    $secretBytes = [System.Text.Encoding]::UTF8.GetBytes(($secretValue | ConvertTo-Json -Compress))
    try {
        $encrypted = [System.Security.Cryptography.ProtectedData]::Protect(
            $secretBytes, $null, [System.Security.Cryptography.DataProtectionScope]::CurrentUser)
        [System.IO.File]::WriteAllBytes($secretPath, $encrypted)
        $passwordInput.Clear()
        [System.Windows.Forms.MessageBox]::Show('已加密保存。可以返回聊天告知配置完成。', '保存完成') | Out-Null
        $form.Close()
    } finally {
        [Array]::Clear($secretBytes, 0, $secretBytes.Length)
    }
})
$form.Controls.AddRange(@($description, $phoneLabel, $phoneInput, $codeLabel, $codeInput, $userLabel, $userInput, $passwordLabel, $passwordInput, $save))
$form.AcceptButton = $save
[void]$form.ShowDialog()
