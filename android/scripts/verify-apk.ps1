param([string]$Apk = "$PSScriptRoot\..\app\build\outputs\apk\debug\app-debug.apk")

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$path = (Resolve-Path -LiteralPath $Apk).Path
$expected = @('Lcom/attentionguard/app/core/NoticeRules;', 'buildEvents', 'isCancelled')
$found = @{}
$zip = [System.IO.Compression.ZipFile]::OpenRead($path)
try {
    foreach ($entry in $zip.Entries | Where-Object Name -Match '^classes.*\.dex$') {
        $stream = $entry.Open()
        $memory = [System.IO.MemoryStream]::new()
        try {
            $stream.CopyTo($memory)
            $text = [System.Text.Encoding]::UTF8.GetString($memory.ToArray())
            foreach ($marker in $expected) {
                if ($text.Contains($marker)) { $found[$marker] = $true }
            }
        } finally {
            $stream.Dispose()
            $memory.Dispose()
        }
    }
} finally {
    $zip.Dispose()
}
$missing = @($expected | Where-Object { !$found.ContainsKey($_) })
if ($missing.Count) { throw "APK contains stale event rules: missing $($missing -join ', '). Rebuild cleanly using one project path." }
[pscustomobject]@{ Apk = $path; Rules = 'Verified'; SHA256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash }
