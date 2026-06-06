# ─────────────────────────────────────────────────────────────────────────────
# build_install.ps1 — Compila myapp y deja el APK listo para instalar
# Uso: .\build_install.ps1
# El APK queda en: app\build\outputs\apk\debug\app-debug.apk
# ─────────────────────────────────────────────────────────────────────────────

$APK_PATH = "app\build\outputs\apk\debug\app-debug.apk"

function OK   { param($m) Write-Host "  [OK] $m" -ForegroundColor Green }
function INFO { param($m) Write-Host "  [..] $m" -ForegroundColor Cyan  }
function FAIL { param($m) Write-Host "  [XX] $m" -ForegroundColor Red   }

Write-Host ""
Write-Host "  myapp - build" -ForegroundColor Cyan
Write-Host ""

# ── JAVA_HOME ─────────────────────────────────────────────────────────────────
$javaDir = "C:\Program Files\Java\jdk-17"
if (Test-Path $javaDir) { $env:JAVA_HOME = $javaDir }

# ── Build ─────────────────────────────────────────────────────────────────────
INFO "Compilando..."
& .\gradlew.bat assembleDebug

if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    FAIL "Compilacion fallida. Revisa los errores arriba."
    exit 1
}

if (-not (Test-Path $APK_PATH)) {
    FAIL "APK no encontrado en: $APK_PATH"
    exit 1
}

$apkSize = [math]::Round((Get-Item $APK_PATH).Length / 1MB, 1)
$apkFull = (Resolve-Path $APK_PATH).Path

Write-Host ""
OK "APK listo ($apkSize MB)"
OK "Ruta: $apkFull"
Write-Host ""
