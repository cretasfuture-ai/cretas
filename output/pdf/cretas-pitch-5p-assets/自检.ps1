$ErrorActionPreference = 'Stop'
python "$PSScriptRoot\check_pdf.py"
if ($LASTEXITCODE -ne 0) { throw 'PDF validation failed' }
$pattern = 'CCP|MES|ERP|DAG|有向无环|节拍|边缘计算|凭证|按量付费|零误差|没有任何误差'
Write-Output "COMMAND: rg -n '$pattern' pdf-text.txt"
$matchesFound = @(rg -n $pattern "$PSScriptRoot\pdf-text.txt")
$rgCode = $LASTEXITCODE
if ($rgCode -gt 1) { throw 'rg failed' }
Write-Output "RG_MATCHES=$($matchesFound.Count); RG_EXIT=$rgCode"
if ($matchesFound.Count -ne 0) { $matchesFound; throw 'Forbidden terms found' }
exit 0
