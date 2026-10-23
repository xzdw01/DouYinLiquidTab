param([string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path)

# The module reads its settings through two independent implementations: the manager side
# (config/LocalSettings.kt) and the hooked side (hook/HookConfigReader.kt). The provider and
# broadcast channels send the whole GlassConfig as a bundle, but the framework's remote
# preferences are rebuilt by HookConfigReader.readConfig on every change -- so a key the
# reader forgets is silently reset to its default mid-session.
#
# This check fails when the manager writes a key the reader never mentions. See the
# internal development notes, "Configuration keys (two readers, one contract)".

$configRoot = Join-Path $RepoRoot 'app/src/main/java/io/github/xzdw/dylt/config'
$readerPath = Join-Path $RepoRoot 'app/src/main/java/io/github/xzdw/dylt/hook/HookConfigReader.kt'

# Retired settings that are deliberately not read on the hooked side. The bottom gradient
# blur is off everywhere in the manager too, so a hard-coded false is consistent.
# KEY_ENABLED is bundle-only: the provider and broadcast payloads carry it, and the
# preference path derives it from the per-package `app_<package>` switch instead.
$allowedUnread = @('KEY_BOTTOM_GRADIENT_BLUR_ENABLED', 'KEY_ENABLED')

$contract = Get-Content -LiteralPath (Join-Path $configRoot 'ConfigContract.kt') -Raw -Encoding utf8
$names = @{}
foreach ($m in [regex]::Matches($contract, 'const val (KEY_[A-Z0-9_]+)\s*=\s*"([^"]+)"')) {
    $names[$m.Groups[1].Value] = $m.Groups[2].Value
}
if ($names.Count -eq 0) { throw 'Could not read any KEY_* constants from ConfigContract.kt.' }

# Keys the manager persists. Empty placeholder arguments (`prefix + KEY_FOO`) are included
# by scanning a short window after each put call.
$writer = Get-Content -LiteralPath (Join-Path $configRoot 'LocalSettings.kt') -Raw -Encoding utf8
$written = @{}
foreach ($m in [regex]::Matches($writer, 'put(?:Boolean|Float|Int|Long|String)\s*\([^,]{0,160}?(KEY_[A-Z0-9_]+)')) {
    $written[$m.Groups[1].Value] = $true
}
if ($written.Count -eq 0) { throw 'Could not read any persisted keys from the manager side.' }

$reader = Get-Content -LiteralPath $readerPath -Raw -Encoding utf8
$read = @{}
foreach ($m in [regex]::Matches($reader, '(KEY_[A-Z0-9_]+)')) { $read[$m.Groups[1].Value] = $true }

$missing = @($written.Keys | Where-Object { -not $read.ContainsKey($_) -and $_ -notin $allowedUnread } | Sort-Object)
if ($missing.Count -gt 0) {
    $detail = ($missing | ForEach-Object { "$_ ($($names[$_]))" }) -join ', '
    throw "HookConfigReader does not read these manager keys, so a live change would reset them: $detail"
}

$extra = @($read.Keys | Where-Object { -not $written.ContainsKey($_) -and $names.ContainsKey($_) } | Sort-Object)
Write-Output ("PASS: {0} persisted keys, {1} read by HookConfigReader, {2} documented exception(s)." -f `
    $written.Count, $read.Count, $allowedUnread.Count)
if ($extra.Count -gt 0) {
    Write-Output ("  note: read but never persisted: " + (($extra | ForEach-Object { $_ }) -join ', '))
}
