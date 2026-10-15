# Generate Android adaptive icon resources from the source illustration.
#
# IMPORTANT: this script deliberately contains NO non-ASCII literals.
# Windows PowerShell 5.1 reads .ps1 files as ANSI by default, so Chinese
# paths written here get mangled into garbage directory names. All paths are
# passed in from the caller and must be ASCII.

param(
    [Parameter(Mandatory = $true)][string]$Source,
    [Parameter(Mandatory = $true)][string]$OutDir
)

Add-Type -AssemblyName System.Drawing

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$src = [System.Drawing.Bitmap]::FromFile($Source)
try {
    $sw = $src.Width
    $sh = $src.Height

    # --- Measure how much of the source canvas the artwork actually covers ---
    # The illustration sits on a white background with margins, so the drawn
    # paper is smaller than the image. We need this ratio to compute the
    # correct scale factor later.
    $bgColor = $src.GetPixel(2, 2)   # corner = background colour
    $minX = $sw; $maxX = 0; $minY = $sh; $maxY = 0
    for ($y = 0; $y -lt $sh; $y += 4) {
        for ($x = 0; $x -lt $sw; $x += 4) {
            $c = $src.GetPixel($x, $y)
            $isBg = [Math]::Abs($c.R - $bgColor.R) -lt 8 -and
                    [Math]::Abs($c.G - $bgColor.G) -lt 8 -and
                    [Math]::Abs($c.B - $bgColor.B) -lt 8
            if (-not $isBg) {
                if ($x -lt $minX) { $minX = $x }
                if ($x -gt $maxX) { $maxX = $x }
                if ($y -lt $minY) { $minY = $y }
                if ($y -gt $maxY) { $maxY = $y }
            }
        }
    }
    $artRatio = [double]($maxX - $minX) / $sw
    Write-Host ("source art covers {0:P1} of the canvas" -f $artRatio)

    # --- Foreground layer ---
    #
    # Adaptive icon geometry:
    #   108dp layer canvas, only the central 66dp is guaranteed visible
    #   (72dp survives typical masks, 66dp survives every mask).
    #
    # The trap: artRatio is the artwork's share OF THE SOURCE IMAGE, so the
    # scale factor is safeZone / artRatio -- NOT the safe zone itself.
    # Scaling by 0.72 would leave the art at ~59% ... but only because this
    # particular art covers 82%. We compute it instead of hardcoding, so the
    # script stays correct if the illustration is swapped.
    $fgSize = 432              # 108dp at 4x
    $safeZone = 0.60           # aim just inside the 61.1% guaranteed area
    $scale = $safeZone / $artRatio
    if ($scale -gt 1.0) { $scale = 1.0 }

    $drawSize = [int]($fgSize * $scale)
    $offset = [int](($fgSize - $drawSize) / 2)
    Write-Host ("foreground scale {0:P1} -> art covers {1:P1} of the icon" -f $scale, ($artRatio * $scale))

    $fg = New-Object System.Drawing.Bitmap($fgSize, $fgSize)
    $g = [System.Drawing.Graphics]::FromImage($fg)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    # Fill with the illustration's own background colour so the scaled-down
    # image edge blends in seamlessly instead of showing a faint square.
    $g.Clear($bgColor)
    $g.DrawImage($src, $offset, $offset, $drawSize, $drawSize)
    $g.Dispose()

    New-Item -ItemType Directory -Force -Path (Join-Path $OutDir "drawable-nodpi") | Out-Null
    $fgPath = Join-Path $OutDir "drawable-nodpi\ic_launcher_foreground.png"
    $fg.Save($fgPath, [System.Drawing.Imaging.ImageFormat]::Png)
    $fg.Dispose()
    Write-Host "foreground -> $fgPath"

    # --- Background layer ---
    # Matches the artwork background; the foreground is fully opaque, so this
    # only matters as a safety net on launchers that composite differently.
    $bg = New-Object System.Drawing.Bitmap(432, 432)
    $gb = [System.Drawing.Graphics]::FromImage($bg)
    $gb.Clear($bgColor)
    $gb.Dispose()
    $bgPath = Join-Path $OutDir "drawable-nodpi\ic_launcher_background.png"
    $bg.Save($bgPath, [System.Drawing.Imaging.ImageFormat]::Png)
    $bg.Dispose()
    Write-Host "background -> $bgPath"

    # --- Legacy icons (Android 7 and older have no adaptive icon support) ---
    #
    # No mask is involved here: the whole square is visible, so the artwork
    # can fill it directly. Only the corners are rounded off.
    $legacySizes = @{
        "mipmap-mdpi"    = 48
        "mipmap-hdpi"    = 72
        "mipmap-xhdpi"   = 96
        "mipmap-xxhdpi"  = 144
        "mipmap-xxxhdpi" = 192
    }
    foreach ($kv in $legacySizes.GetEnumerator()) {
        $size = $kv.Value
        $bmp = New-Object System.Drawing.Bitmap($size, $size)
        $g2 = [System.Drawing.Graphics]::FromImage($bmp)
        $g2.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $g2.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
        $g2.Clear([System.Drawing.Color]::Transparent)

        $radius = [int]($size * 0.22)
        $path = New-Object System.Drawing.Drawing2D.GraphicsPath
        $d = $radius * 2
        $path.AddArc(0, 0, $d, $d, 180, 90)
        $path.AddArc($size - $d, 0, $d, $d, 270, 90)
        $path.AddArc($size - $d, $size - $d, $d, $d, 0, 90)
        $path.AddArc(0, $size - $d, $d, $d, 90, 90)
        $path.CloseFigure()
        $g2.SetClip($path)
        $g2.DrawImage($src, 0, 0, $size, $size)
        $g2.Dispose()
        $path.Dispose()

        $dir = Join-Path $OutDir $kv.Key
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
        $out = Join-Path $dir "ic_launcher.png"
        $bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
        $bmp.Dispose()
        Write-Host "legacy ${size}x${size} -> $out"
    }
}
finally {
    $src.Dispose()
}

Write-Host ""
Write-Host "icons generated into $OutDir"
