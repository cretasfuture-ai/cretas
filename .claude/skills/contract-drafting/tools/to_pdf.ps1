# 用法: pwsh -File to_pdf.ps1 -OutDir <docx目录> -Jobs 'a.docx=true;b.docx=false'   (true = 加目录 + 附件分页)
param([string]$OutDir, [string]$Jobs)

$ErrorActionPreference = 'Stop'
$PdfDir = Join-Path $OutDir 'pdf'
if (-not (Test-Path $PdfDir)) { New-Item -ItemType Directory -Path $PdfDir | Out-Null }

# 文件 -> 是否加目录
$JobMap = [ordered]@{}
foreach ($kv in ($Jobs -split ';')) { if ($kv.Trim()) { $k,$v = $kv -split '=',2; $JobMap[$k.Trim()] = ($v.Trim() -eq 'true') } }

# Word 内置样式常量（用数字避开中文/英文版样式名差异）
$wdStyleTOC1 = -20; $wdStyleTOC2 = -21; $wdStyleTOC3 = -22
$wdStyleHeading1 = -2; $wdStyleTitle = -63
$wdPageBreak = 7
$wdAlignParagraphCenter = 1
$wdExportFormatPDF = 17

$word = New-Object -ComObject Word.Application
$word.Visible = $false
$word.DisplayAlerts = 0

try {
    foreach ($name in $JobMap.Keys) {
        $src = Join-Path $OutDir $name
        $pdf = Join-Path $PdfDir ($name -replace '\.docx$', '.pdf')
        $wantToc = $JobMap[$name]

        $doc = $word.Documents.Open($src, $false, $false)   # 不只读，要保存目录

        if ($wantToc) {
            # 合同标题本身不该出现在目录里。它是 Heading1，而附件一~八 也是 Heading1，
            # 所以不能靠调目录级别排除它 —— 把标题这一段改成非标题样式(Title)，
            # 外观不变（同样黑体小二居中），但目录域只认 Heading 样式，就跳过它了。
            $title = $doc.Paragraphs.Item(1)
            $title.Range.Style = $doc.Styles.Item($wdStyleTitle)
            $title.Range.Font.Name = 'SimHei'
            $title.Range.Font.NameFarEast = 'SimHei'
            $title.Range.Font.Size = 18
            $title.Range.Font.Color = 0
            $title.Format.Alignment = $wdAlignParagraphCenter
            $title.Format.CharacterUnitFirstLineIndent = 0
            $title.Format.FirstLineIndent = 0

            # 每个附件（仍是 Heading1）另起一页
            $h1Name = $doc.Styles.Item($wdStyleHeading1).NameLocal
            foreach ($p in $doc.Paragraphs) {
                if ($p.Style.NameLocal -eq $h1Name) { $p.Format.PageBreakBefore = $true }
            }

            # 第 1 段 = 合同标题，第 2 段 = 供审阅稿声明。目录插在其后。
            $anchor = $doc.Paragraphs.Item(2).Range
            $anchor.InsertParagraphAfter()

            # 「目　录」抬头：黑体三号居中，不用 Heading 样式（否则它自己会进目录）
            $cap = $doc.Paragraphs.Item(3)
            # ⛔ 不要用 $cap.Range.Text = '...' —— 段落 Range 含段落标记，赋值会把它
            #    一起替换掉，结果是抬头和下一段（合同编号）粘成一行（实测踩过）。
            $cap.Range.InsertBefore('目　录')
            $cap.Range.Font.Name = 'SimHei'
            $cap.Range.Font.NameFarEast = 'SimHei'
            $cap.Range.Font.Size = 16
            $cap.Range.Font.Bold = $false
            $cap.Range.Font.Color = 0
            $cap.Format.Alignment = $wdAlignParagraphCenter
            $cap.Format.CharacterUnitFirstLineIndent = 0
            $cap.Format.FirstLineIndent = 0
            $cap.Format.SpaceAfter = 12

            # 目录域：只收一、二级（附件 + 各条），右对齐页码，不做超链接（避免蓝字）
            $cap.Range.InsertParagraphAfter()
            $tocRange = $doc.Paragraphs.Item(4).Range
            $toc = $doc.TablesOfContents.Add(
                $tocRange, $true, 1, 2, $false, '', $true, $true, '', $false, $true, $true)

            # 目录之后分页，正文从新的一页开始
            $endPos = $toc.Range.End
            $brk = $doc.Range($endPos, $endPos)
            $brk.InsertBreak($wdPageBreak)

            # 目录条目也压成宋体黑字，不缩进
            foreach ($sid in @($wdStyleTOC1, $wdStyleTOC2, $wdStyleTOC3)) {
                $st = $doc.Styles.Item($sid)
                $st.Font.Name = 'SimSun'
                $st.Font.NameFarEast = 'SimSun'
                $st.Font.Color = 0
                $st.Font.Size = 12
                $st.ParagraphFormat.CharacterUnitFirstLineIndent = 0
                $st.ParagraphFormat.FirstLineIndent = 0
            }

            $doc.Repaginate()
            $toc.Update()
        }

        # 表格：pandoc 不写列宽，Word 默认分配很差（实测「一期交付范围」列被挤成一条）。
        # 先按内容量一遍，再按版心宽度等比放大，最后让表头在跨页时重复。
        $usable = $doc.PageSetup.PageWidth - $doc.PageSetup.LeftMargin - $doc.PageSetup.RightMargin
        $minW = 44          # 磅，约 1.55cm ≈ 4 个汉字
        foreach ($tbl in $doc.Tables) {
            $tbl.AutoFitBehavior(1)          # wdAutoFitContent
            $tbl.AutoFitBehavior(2)          # wdAutoFitWindow

            # 中文没有词边界，Word 认为「性质」这种窄表头最小只需 1 字宽，
            # 于是把它压成一条竖带（实测「一期实施」竖排成 3-4 行）。给窄列兜下限。
            #
            # ⛔ 不要按磅逐列赋 .Width：Word 会级联推挤后面的列，实测第 6 列没被抬到
            #    下限、总宽还从 415.6 涨到 459 磅（表格溢出版心），而报错被 catch 吞掉。
            # ✅ 改成按百分比：表宽锁 100%，每列给一个占比，Word 在版心内自行分配。
            $minPct = 100.0 * $minW / $usable
            try {
                $w = @(); foreach ($c in $tbl.Columns) { $w += [double]$c.Width }
                $tot = ($w | Measure-Object -Sum).Sum
                if ($tot -gt 0) {
                    $p = @(); foreach ($x in $w) { $p += (100.0 * $x / $tot) }
                    $deficit = 0.0; $surplus = 0.0
                    foreach ($x in $p) {
                        if ($x -lt $minPct) { $deficit += ($minPct - $x) }
                        else { $surplus += ($x - $minPct) }
                    }
                    if ($deficit -gt 0 -and $surplus -gt $deficit) {
                        for ($i = 0; $i -lt $p.Count; $i++) {
                            if ($p[$i] -lt $minPct) { $p[$i] = $minPct }
                            else { $p[$i] = $p[$i] - $deficit * ($p[$i] - $minPct) / $surplus }
                        }
                    }
                    $tbl.AllowAutoFit = $false
                    $tbl.PreferredWidthType = 2          # wdPreferredWidthPercent
                    $tbl.PreferredWidth = 100
                    for ($i = 0; $i -lt $p.Count; $i++) {
                        $col = $tbl.Columns.Item($i + 1)
                        $col.PreferredWidthType = 2
                        $col.PreferredWidth = $p[$i]
                    }
                }
            }
            catch {
                # ⛔ 不静默吞：吞掉的正是「只改了一半」那种最难发现的失败
                Write-Output ("     !! 表格列宽调整失败(" + $name + "): " + $_.Exception.Message)
            }

            $tbl.Rows.Item(1).HeadingFormat = $true
        }

        $doc.Fields.Update() | Out-Null      # 页脚页码域
        $doc.Repaginate()
        $pages = $doc.ComputeStatistics(2)   # wdStatisticPages

        $doc.Save()
        $doc.ExportAsFixedFormat($pdf, $wdExportFormatPDF)
        $doc.Close($false)

        '{0,-42} 目录={1,-5} 页数={2}' -f $name, $wantToc, $pages
    }
}
finally {
    $word.Quit()
    [System.Runtime.InteropServices.Marshal]::ReleaseComObject($word) | Out-Null
}
'PDF_DONE'
