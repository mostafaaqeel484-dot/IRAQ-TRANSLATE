$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$Destination = Join-Path $Root 'app/libs/sherpa-onnx-static-link-onnxruntime-1.12.15.aar'
$Partial = "$Destination.part"
$Url = 'https://huggingface.co/csukuangfj/sherpa-onnx-libs/resolve/dea0ed4bc892fe6725c87874bea5c2e7cc946132/android/aar/1.12.15/sherpa-onnx-static-link-onnxruntime-1.12.15.aar?download=true'
$Expected = '350d4a42a7ccde5a3b7dda207f954d6d57de981fe747106b0e3091e7b947c236'
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Destination) | Out-Null
if (Test-Path $Destination) {
    $Existing = (Get-FileHash -Algorithm SHA256 $Destination).Hash.ToLowerInvariant()
    if ($Existing -eq $Expected) { Write-Host 'Sherpa-ONNX AAR already verified.'; exit 0 }
}
Remove-Item $Partial -Force -ErrorAction SilentlyContinue
Invoke-WebRequest -Uri $Url -OutFile $Partial
$Actual = (Get-FileHash -Algorithm SHA256 $Partial).Hash.ToLowerInvariant()
if ($Actual -ne $Expected) { Remove-Item $Partial -Force; throw "Sherpa AAR SHA-256 mismatch: $Actual" }
Move-Item -Force $Partial $Destination
Write-Host "Sherpa-ONNX AAR downloaded and verified: $Destination"
