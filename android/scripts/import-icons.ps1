param([string[]]$Only = @())
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$revision = '0.468.0'
$names = @('focus','notebook-tabs','messages-square','sliders-horizontal','settings-2','arrow-left','chevron-right','chevron-down','check','undo-2','clock-3','search','x','shield-check','key-round','radio','pause','circle-alert','arrow-up-right','eye','move','minimize-2','rotate-ccw','save','wifi','check-check','archive','bookmark-plus','scan-text','activity','smile','ellipsis','grip-horizontal','circle-check','calendar-plus')
if ($Only.Count) { $names = $names | Where-Object { $_ -in $Only } }
$source = Join-Path $root 'docs/brand/lucide'
$dest = Join-Path $root 'app/src/main/res/drawable'
New-Item -ItemType Directory -Force $source | Out-Null
foreach ($name in $names) {
    $file = Join-Path $source "$name.svg"
    if (-not (Test-Path -LiteralPath $file)) {
        Invoke-WebRequest "https://raw.githubusercontent.com/lucide-icons/lucide/$revision/icons/$name.svg" -OutFile $file
    }
    [xml]$svg = Get-Content -Raw -LiteralPath $file
    $paths = foreach ($node in $svg.svg.ChildNodes) {
        $d = switch ($node.LocalName) {
            'path' { $node.d }
            'line' { "M$($node.x1),$($node.y1) L$($node.x2),$($node.y2)" }
            'polyline' { 'M' + $node.points }
            'polygon' { 'M' + $node.points + ' Z' }
            'circle' {
                $cx = [double]$node.cx; $cy = [double]$node.cy; $r = [double]$node.r
                "M$($cx-$r),$cy a$r,$r 0 1,0 $($r*2),0 a$r,$r 0 1,0 $(-$r*2),0"
            }
            'rect' {
                $x = [double]$node.x; $y = [double]$node.y; $w = [double]$node.width; $h = [double]$node.height; $r = [double]$node.rx
                "M$($x+$r),$y H$($x+$w-$r) Q$($x+$w),$y $($x+$w),$($y+$r) V$($y+$h-$r) Q$($x+$w),$($y+$h) $($x+$w-$r),$($y+$h) H$($x+$r) Q$x,$($y+$h) $x,$($y+$h-$r) V$($y+$r) Q$x,$y $($x+$r),$y Z"
            }
            default { throw "Unsupported SVG element $($node.LocalName) in $name" }
        }
        '    <path android:fillColor="@android:color/transparent" android:strokeColor="@color/ag_ink" android:strokeWidth="1.8" android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="' + [System.Security.SecurityElement]::Escape($d) + '" />'
    }
    $xml = '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">' + "`n" + ($paths -join "`n") + "`n</vector>`n"
    [System.IO.File]::WriteAllText((Join-Path $dest ('ag_' + $name.Replace('-', '_') + '.xml')), $xml)
}
Invoke-WebRequest "https://raw.githubusercontent.com/lucide-icons/lucide/$revision/LICENSE" -OutFile (Join-Path $source 'LICENSE')
Write-Output "Imported $($names.Count) Lucide icons at $revision"
