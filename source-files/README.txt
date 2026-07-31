SOURCE FILES FOR MASTER DATA
============================

Drop the following files in this folder, then run:

    powershell -ExecutionPolicy Bypass -File app\Import-Masters.ps1

to (re)build app\data\staff.json and app\data\villages.json.

Expected files (auto-discovered by name pattern):
  1. GS*.xlsx        — one or more GDS pay-bill exports (one per DDO / sub-account
                       office). Must contain columns: Employee_name, Post_desc,
                       Office_desc, Level, Date_of_birth, Date_of_join, etc.
  2. *village*.docx  — the "village sorting List ... .docx" with one table per BO,
                       each preceded by a "VILLAGE SORTING LIST OF <BO> BO A\W ..." heading.

Requirements: Windows + Microsoft Excel (read) + PowerShell.

If a source file must stay outside the project (e.g. a live G:\ network path),
copy app\data\sources.json.example to app\data\sources.json and point it there
instead of placing the file here.
