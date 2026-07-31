<#
  Build-Template.ps1
  Normalizes the master Inspection Report DOCX into a placeholdered template.

  Rules honoured:
    * The original DOCX is cloned and edited at the OOXML level (never HTML-to-Word).
    * Static official clauses are left untouched (clause library territory).
    * Office-specific scalars -> {{ key }} in a single run (formatting preserved).
    * Repeatable data rows -> ONE template row with {{ loopVar.field }} tokens.
    * Paired (2-up) tables are collapsed to a single record per row.
    * Header rows get w:tblHeader (repeat on page break); data rows get cantSplit.

  Output: templates\IR_BO_TEMPLATE.docx
#>
[CmdletBinding()]
param(
  [string]$Source = "$PSScriptRoot\..\templates\IR_MASTER_TEMPLATE.docx",
  [string]$OutTemplate = "$PSScriptRoot\..\templates\IR_BO_TEMPLATE.docx"
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

$W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
$Source = (Resolve-Path $Source).Path
$work = Join-Path $env:TEMP ("irtpl_" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work -Force | Out-Null
[System.IO.Compression.ZipFile]::ExtractToDirectory($Source, $work)
$docPath = Join-Path $work 'word\document.xml'
[xml]$doc = Get-Content -Raw -Encoding UTF8 $docPath
$ns = New-Object System.Xml.XmlNamespaceManager($doc.NameTable)
$ns.AddNamespace('w', $W)

# ---------- helpers -------------------------------------------------------
function New-Run([string]$text, [bool]$bold = $false) {
  $r = $doc.CreateElement('w', 'r', $W)
  if ($bold) { $rpr = $doc.CreateElement('w', 'rPr', $W); [void]$rpr.AppendChild($doc.CreateElement('w', 'b', $W)); [void]$r.AppendChild($rpr) }
  $t = $doc.CreateElement('w', 't', $W)
  [void]$t.SetAttribute('space', 'http://www.w3.org/XML/1998/namespace', 'preserve')
  $t.InnerText = $text
  [void]$r.AppendChild($t)
  return $r
}
function Set-ParaText($p, [string]$text, [bool]$bold = $false) {
  foreach ($r in @($p.SelectNodes('w:r', $ns))) { [void]$p.RemoveChild($r) }
  foreach ($h in @($p.SelectNodes('w:hyperlink', $ns))) { [void]$p.RemoveChild($h) }
  [void]$p.AppendChild((New-Run $text $bold))
}
function Set-CellText($tc, [string]$text, [bool]$bold = $false) {
  $paras = @($tc.SelectNodes('w:p', $ns))
  if ($paras.Count -eq 0) { return }
  Set-ParaText $paras[0] $text $bold
  for ($i = 1; $i -lt $paras.Count; $i++) { foreach ($r in @($paras[$i].SelectNodes('w:r', $ns))) { [void]$paras[$i].RemoveChild($r) } }
}
function Rows($tbl) { , @($tbl.SelectNodes('w:tr', $ns)) }
function Cells($tr) { , @($tr.SelectNodes('w:tc', $ns)) }
function Get-BodyParas { $l = @(); foreach ($n in $doc.document.body.ChildNodes) { if ($n.LocalName -eq 'p') { $l += $n } }; , $l }
function Get-Tables { , @($doc.document.body.SelectNodes('w:tbl', $ns)) }

# Remove whole columns (0-based grid indices) from simple rows; shrink gridSpan on spanned rows.
function Remove-Columns($tbl, [int[]]$cols) {
  $origCols = @($tbl.SelectNodes('w:tblGrid/w:gridCol', $ns)).Count
  $newCount = $origCols - $cols.Count
  $desc = $cols | Sort-Object -Descending
  foreach ($tr in (Rows $tbl)) {
    $cs = Cells $tr
    if ($cs.Count -eq $origCols) {
      foreach ($c in $desc) { [void]$tr.RemoveChild($cs[$c]) }
    }
    elseif ($cs.Count -eq 1) {
      $gs = $cs[0].SelectSingleNode('w:tcPr/w:gridSpan', $ns)
      if ($gs) { [void]$gs.SetAttribute('val', $W, [string]$newCount) }
    }
  }
  $gridCols = @($tbl.SelectNodes('w:tblGrid/w:gridCol', $ns))
  foreach ($c in $desc) { [void]$gridCols[$c].ParentNode.RemoveChild($gridCols[$c]) }
}

# Convert a table to header + one template row.
#   trailingKeep = number of rows at the end to preserve (e.g. note rows)
function Set-RepeatableTable($tbl, [int]$headerRows, [int]$firstData, [string[]]$cellTokens, [int]$trailingKeep = 0) {
  $rows = Rows $tbl
  $donor = $rows[$firstData - 1]
  $cs = Cells $donor
  for ($c = 0; $c -lt $cellTokens.Count -and $c -lt $cs.Count; $c++) { Set-CellText $cs[$c] $cellTokens[$c] }
  $lastDel = $rows.Count - 1 - $trailingKeep
  for ($i = $headerRows; $i -le $lastDel; $i++) { if ($rows[$i] -ne $donor) { [void]$tbl.RemoveChild($rows[$i]) } }
}

# Add a trPr child element (e.g. tblHeader / cantSplit) to a row.
function Add-RowProp($tr, [string]$name) {
  $trPr = $tr.SelectSingleNode('w:trPr', $ns)
  if (-not $trPr) { $trPr = $doc.CreateElement('w', 'trPr', $W); [void]$tr.PrependChild($trPr) }
  if (-not $trPr.SelectSingleNode("w:$name", $ns)) { [void]$trPr.AppendChild($doc.CreateElement('w', $name, $W)) }
}

$P = Get-BodyParas
$T = Get-Tables
Write-Host ("Loaded: {0} paragraphs, {1} tables" -f $P.Count, $T.Count)

# ===== TITLE & INTRO =====================================================
Set-ParaText $P[0] 'INSPECTION REPORT ON {{office_name}} {{office_type}} A/W {{account_office}} SO - {{account_office_pin}} DATED {{inspection_date}}' $true
Set-ParaText $P[2] 'Paid a {{visit_kind}} to {{office_name}} {{office_type}} a/w {{account_office}} SO on {{inspection_date}} and carried out its {{inspection_nature}} inspection for the year {{inspection_year}}.'

# ===== TABLE 1 : general info (key/value col 2) ==========================
$t1 = $T[0]; $r1 = Rows $t1
$t1map = @('{{office_name}}', '{{account_office}}', '{{head_office}}', '{{facility_id}}', '{{profit_centre_id}}',
  '{{date_of_opening}}', '{{div_head_last_visit}}', '{{last_inspection_date}}', '{{last_inspected_by}}',
  '{{subdiv_head_visits}}', '{{mail_overseer_visits}}')
for ($i = 0; $i -lt $t1map.Count; $i++) { Set-CellText (Cells $r1[$i + 1])[2] $t1map[$i] }

# ===== TABLE 2 : technical info (index-addressed, merges preserved) ======
$t2 = $T[1]; $r2 = Rows $t2
Set-CellText (Cells $r2[1])[1] 'Working hours {{working_hours}}'
Set-CellText (Cells $r2[1])[3] '{{wh_receipt}}'
Set-CellText (Cells $r2[1])[4] '{{tech_remark_hours}}'
Set-CellText (Cells $r2[2])[3] '{{wh_delivery}}'
Set-CellText (Cells $r2[3])[3] '{{wh_lb_clearance}}'
Set-CellText (Cells $r2[4])[3] '{{wh_dispatch}}'
Set-CellText (Cells $r2[5])[3] '{{auth_min_cash}}/-'
Set-CellText (Cells $r2[5])[4] '{{tech_remark_balance}}'
Set-CellText (Cells $r2[6])[3] '{{auth_max_cash}}/-'
Set-CellText (Cells $r2[7])[3] '{{postage_stamp_balance}}/-'
Set-CellText (Cells $r2[8])[3] '{{revenue_stamp_balance}}/-'
Set-CellText (Cells $r2[9])[3] '{{location_type}}'
Set-CellText (Cells $r2[9])[4] '{{location_remark}}'
Set-CellText (Cells $r2[10])[2] '{{building_condition}}'
Set-CellText (Cells $r2[11])[2] '{{cleanliness_remark}}'
Set-CellText (Cells $r2[12])[2] '{{villages_served}}'
Set-CellText (Cells $r2[13])[2] '{{households_remark}}'
Set-CellText (Cells $r2[14])[2] '{{services_remark}}'
Set-CellText (Cells $r2[15])[2] '{{panchayat_hq}}'
Set-CellText (Cells $r2[16])[2] '{{mail_arrangement}}'
Set-CellText (Cells $r2[17])[2] '{{fg_bonds_remark}}'

# ===== TABLE 3 : establishment (repeatable) =============================
Set-RepeatableTable $T[2] 1 2 @('{{establishment.#}}', '{{establishment.name}}', '{{establishment.designation}}',
  '{{establishment.employee_id}}', '{{establishment.trca}}', '{{establishment.dob}}', '{{establishment.doa}}', '{{establishment.remarks}}')

# ===== TABLE 4 : DARPAN devices (repeatable) ============================
Set-RepeatableTable $T[3] 1 2 @('{{devices.#}}', '{{devices.device}}', '{{devices.serial}}', '{{devices.version}}')

# ===== TABLE 5 : device/user info (key/value col 1) =====================
$t5 = $T[4]; $r5 = Rows $t5
$t5vals = @('{{office_name}} {{office_type}}', '{{facility_id}}', '{{user_name}}', '{{user_id}}', '{{user_role}}')
for ($i = 0; $i -lt $t5vals.Count; $i++) { Set-CellText (Cells $r5[$i])[1] $t5vals[$i] }

# ===== TABLE 6 : articles in deposit (repeatable) =======================
Set-RepeatableTable $T[5] 1 2 @('{{deposit_articles.#}}', '{{deposit_articles.article_no}}', '{{deposit_articles.received_date}}', '{{deposit_articles.remarks}}')

# ===== TABLE 7 : random BO balance checks (repeatable) =================
Set-RepeatableTable $T[6] 1 2 @('{{bo_balance_checks.date}}', '{{bo_balance_checks.bal_per_account}}', '{{bo_balance_checks.bal_acknowledged}}')

# ===== TABLE 8 : MO receipts MS-87a (2-up paired, layout preserved) =====
Set-RepeatableTable $T[7] 1 2 @('{{mo_receipts.rect_no}}', '{{mo_receipts.date}}', '{{mo_receipts.amount}}',
  '{{mo_receipts.rect_no@2}}', '{{mo_receipts.date@2}}', '{{mo_receipts.amount@2}}')

# ===== TABLE 9 : MO paid / VP-COD (repeatable) =========================
Set-RepeatableTable $T[8] 1 2 @('{{vpcod_checks.date}}', '{{vpcod_checks.mo_paid}}', '{{vpcod_checks.article_no}}',
  '{{vpcod_checks.date_receipt}}', '{{vpcod_checks.date_delivery}}', '{{vpcod_checks.amount}}')

# ===== TABLE 10 : SB-26 receipts (2-up paired + note, layout preserved) =
$t10 = $T[9]
Set-RepeatableTable $t10 1 2 @('{{sb26_receipts.book_no}}', '{{sb26_receipts.rpt_no}}', '{{sb26_receipts.date}}', '{{sb26_receipts.amount}}',
  '{{sb26_receipts.rpt_no@2}}', '{{sb26_receipts.date@2}}', '{{sb26_receipts.amount@2}}', '{{sb26_receipts.remarks+}}') 1
$r10 = Rows $t10
Set-CellText (Cells $r10[$r10.Count - 1])[0] '{{sb26_note}}'

# ===== TABLE 11 : passbooks by scheme (repeatable) =====================
Set-RepeatableTable $T[10] 1 2 @('{{passbooks_checked.#}}', '{{passbooks_checked.scheme}}', '{{passbooks_checked.account_no}}',
  '{{passbooks_checked.dlt}}', '{{passbooks_checked.deposit}}', '{{passbooks_checked.withdrawal}}', '{{passbooks_checked.bat}}', '{{passbooks_checked.remarks}}')

# ===== TABLE 12 : SB-28 receipts (2-up paired + note, layout preserved) =
$t12 = $T[11]
Set-RepeatableTable $t12 1 2 @('{{sb28_receipts.book_no}}', '{{sb28_receipts.receipt_no}}', '{{sb28_receipts.date}}',
  '{{sb28_receipts.receipt_no@2}}', '{{sb28_receipts.date@2}}') 1
$r12 = Rows $t12
Set-CellText (Cells $r12[$r12.Count - 1])[0] '{{sb28_note}}'

# ===== TABLE 14 : SSA accounts collected (repeatable) ==================
Set-RepeatableTable $T[13] 1 2 @('{{ssa_collected.#}}', '{{ssa_collected.account_no}}', '{{ssa_collected.name}}', '{{ssa_collected.cif}}', '{{ssa_collected.address}}')

# ===== TABLE 15 : SB accounts collected (repeatable) ===================
Set-RepeatableTable $T[14] 1 2 @('{{sb_collected.#}}', '{{sb_collected.account_no}}', '{{sb_collected.name}}', '{{sb_collected.cif}}', '{{sb_collected.address}}')

# ===== TABLE 16 : transaction totals (repeatable) ======================
Set-RepeatableTable $T[15] 1 2 @('{{txn_totals.date}}', '{{txn_totals.sb_dep}}', '{{txn_totals.rd_dep}}', '{{txn_totals.rd_df}}',
  '{{txn_totals.ssa_dep}}', '{{txn_totals.sb_wd}}', '{{txn_totals.ippb_wd}}')

# ===== TABLE 17 : discontinued RD accounts (repeatable) ================
Set-RepeatableTable $T[16] 1 2 @('{{discontinued_accounts.#}}', '{{discontinued_accounts.account_no}}', '{{discontinued_accounts.dlt}}', '{{discontinued_accounts.transaction}}', '{{discontinued_accounts.bat}}')

# ===== TABLE 18 : PLI/RPLI premium collection (2-up paired) ============
Set-RepeatableTable $T[17] 1 2 @('{{pli_collection.date}}', '{{pli_collection.amount}}',
  '{{pli_collection.date@2}}', '{{pli_collection.amount@2}}')

# ===== TABLE 19 : PLI/RPLI policy passbooks (repeatable) ===============
Set-RepeatableTable $T[18] 1 2 @('{{pli_passbooks.#}}', '{{pli_passbooks.policy_no}}', '{{pli_passbooks.date}}', '{{pli_passbooks.premium}}', '{{pli_passbooks.paid_upto}}')

# ===== TABLE 20 : business development new accounts (single row) ========
$t20 = $T[19]; $r20 = Rows $t20
$bd = @('{{bd_details}}', '{{bd_sb}}', '{{bd_rd}}', '{{bd_ssa}}', '{{bd_td}}', '{{bd_pli_no}}', '{{bd_pli_prm}}', '{{bd_pli_sa}}', '{{bd_rpli_no}}', '{{bd_rpli_prm}}', '{{bd_rpli_sa}}')
$dataRow = Cells $r20[2]
for ($i = 0; $i -lt $bd.Count -and $i -lt $dataRow.Count; $i++) { Set-CellText $dataRow[$i] $bd[$i] }

# ===== TABLE 22 : IPPB quarterly transactions (repeatable) =============
Set-RepeatableTable $T[21] 1 2 @('{{ippb_txns.date}}', '{{ippb_txns.dep}}', '{{ippb_txns.wdl}}')

# ===== TABLE 23 : conclusion scoring ===================================
$t23 = $T[22]; $r23 = Rows $t23
$scoreKeys = @('records', 'hardware', 'rict', 'ippb', 'bd', 'service')
for ($i = 0; $i -lt $scoreKeys.Count; $i++) {
  Set-CellText (Cells $r23[$i + 1])[2] ('{{score_' + $scoreKeys[$i] + '}}')
  Set-CellText (Cells $r23[$i + 1])[3] ('{{remarks_' + $scoreKeys[$i] + '}}')
}

# ===== RESULT / MEMO / COPY-TO / SIGNATURE =============================
Set-ParaText $P[203] 'Result of inspection is {{conclusion_result}} except the omissions pointed out in foregoing paras.'
Set-ParaText $P[205] '60.      BPM should paste this IR neatly in the order book and submit compliance report to Divisional office within {{compliance_days}} days duly noting marginal remarks in the order book.'
Set-ParaText $P[206] 'MEMO: {{memo_number}} dated {{memo_place}} the {{memo_date}}' $true
Set-ParaText $P[208] 'The SPOs, {{division}}, {{division_pin}}.'
Set-ParaText $P[209] 'BPM, {{office_name}} {{office_type}} {{account_office_pin}}.'
Set-ParaText $P[210] '//{{signatory_name}}// {{signatory_designation}}'

# ===== CLAUSE-DRIVEN PARAGRAPHS (Sections B, F, J) =====================
# Replace each clean single-paragraph numbered point with "<num>. {{clause_ID}}".
# Numbering prefix is preserved verbatim (official numbering never auto-renumbered).
# Default outcome renders the approved 'satisfactory' clause from clauses.json.
$clauseParas = @(
  @(34, '11', 'B11'), @(35, '12', 'B12'), @(36, '13', 'B13'), @(37, '14', 'B14'), @(38, '15', 'B15'),
  @(39, '16.1', 'B16_1'), @(40, '16.2', 'B16_2'), @(41, '16.3', 'B16_3'), @(42, '16.4', 'B16_4'), @(43, '16.5', 'B16_5'),
  @(44, '16.6', 'B16_6'), @(45, '16.7', 'B16_7'), @(46, '16.8', 'B16_8'), @(47, '16.9', 'B16_9'), @(48, '16.10', 'B16_10'),
  @(49, '16.11', 'B16_11'), @(50, '16.12', 'B16_12'),
  @(141, '49.1', 'F49_1'), @(142, '49.2', 'F49_2'), @(143, '49.3', 'F49_3'), @(144, '49.4', 'F49_4'), @(145, '49.5', 'F49_5'),
  @(146, '49.6', 'F49_6'), @(147, '49.7', 'F49_7'), @(148, '49.8', 'F49_8'), @(149, '49.9', 'F49_9'), @(151, '49.10', 'F49_10'),
  @(153, '49.11', 'F49_11'), @(155, '49.12', 'F49_12'), @(156, '49.13', 'F49_13'), @(157, '49.14', 'F49_14'), @(158, '49.15', 'F49_15'),
  @(159, '49.16', 'F49_16'), @(160, '49.17', 'F49_17'), @(161, '49.18', 'F49_18'),
  @(186, '58.1', 'J58_1'), @(187, '58.2', 'J58_2'), @(188, '58.3', 'J58_3'), @(189, '58.4', 'J58_4'), @(190, '58.5', 'J58_5'),
  @(191, '58.6', 'J58_6'), @(192, '58.7', 'J58_7'), @(193, '58.8', 'J58_8'), @(194, '58.9', 'J58_9'), @(196, '58.10', 'J58_10'),
  @(197, '58.11', 'J58_11'), @(198, '58.12', 'J58_12'), @(199, '58.13', 'J58_13')
)
foreach ($cp in $clauseParas) { Set-ParaText $P[$cp[0]] ($cp[1] + '. {{clause_' + $cp[2] + '}}') }

# ===== HEADER REPEAT + cantSplit (all tables) ==========================
foreach ($tbl in (Get-Tables)) {
  $rows = Rows $tbl
  if ($rows.Count -ge 1) { Add-RowProp $rows[0] 'tblHeader' }
  foreach ($tr in $rows) { Add-RowProp $tr 'cantSplit' }
}

# ===== save & repackage ================================================
$settings = New-Object System.Xml.XmlWriterSettings
$settings.Encoding = New-Object System.Text.UTF8Encoding($false)
$settings.Indent = $false
$wr = [System.Xml.XmlWriter]::Create($docPath, $settings)
$doc.Save($wr); $wr.Close()

if (Test-Path $OutTemplate) { Remove-Item $OutTemplate -Force }
[System.IO.Compression.ZipFile]::CreateFromDirectory($work, $OutTemplate)
Write-Host ("TEMPLATE WRITTEN: {0}" -f (Resolve-Path $OutTemplate).Path) -ForegroundColor Green
