# Import-Posts.ps1
# Converts the Sanctioned Posts Excel file to JSON

$ErrorActionPreference = "Stop"
$excelPath = "C:\Users\admin\Desktop\SDO\Posts Karur Sub Division.xlsx"
$jsonPath = "C:\Users\admin\Desktop\SDO\app\data\posts.json"

Write-Host "Reading $excelPath..."
$excel = New-Object -ComObject Excel.Application
$excel.Visible = $false
$excel.DisplayAlerts = $false

try {
    $workbook = $excel.Workbooks.Open($excelPath)
    $worksheet = $workbook.Sheets.Item(1)
    $usedRange = $worksheet.UsedRange
    
    $colCount = $usedRange.Columns.Count
    $rowCount = $usedRange.Rows.Count
    
    $headers = @()
    for ($col = 1; $col -le $colCount; $col++) {
        $headers += $worksheet.Cells.Item(1, $col).Text
    }
    
    $data = @()
    for ($row = 2; $row -le $rowCount; $row++) {
        $obj = [PSCustomObject]@{}
        for ($col = 1; $col -le $colCount; $col++) {
            $header = $headers[$col - 1]
            $value = $worksheet.Cells.Item($row, $col).Text
            $obj | Add-Member -MemberType NoteProperty -Name $header -Value $value
        }
        $data += $obj
    }
    
    $data | ConvertTo-Json -Depth 10 | Out-File -FilePath $jsonPath -Encoding utf8
    Write-Host "Successfully exported $($data.Count) posts to $jsonPath"
}
finally {
    if ($workbook) { $workbook.Close($false) }
    $excel.Quit()
    [System.Runtime.Interopservices.Marshal]::ReleaseComObject($excel) | Out-Null
    [System.GC]::Collect()
    [System.GC]::WaitForPendingFinalizers()
}
