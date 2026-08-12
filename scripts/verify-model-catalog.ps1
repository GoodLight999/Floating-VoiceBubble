$ErrorActionPreference = 'Stop'

$Base = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models'
$Urls = @(
    "$Base/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-80ms-int8-2026-06-11.tar.bz2",
    "$Base/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-160ms-int8-2026-06-11.tar.bz2",
    "$Base/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11.tar.bz2",
    "$Base/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-1120ms-int8-2026-06-11.tar.bz2",
    "$Base/sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01.tar.bz2",
    'https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm?download=true',
    'https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm?download=true'
)

foreach ($Url in $Urls) {
    Write-Host "Probing $Url"
    $Succeeded = $false
    for ($Attempt = 1; $Attempt -le 3 -and -not $Succeeded; $Attempt++) {
        try {
            $Response = Invoke-WebRequest -Uri $Url -Method Head -MaximumRedirection 8 -TimeoutSec 60 -UseBasicParsing
            if ($Response.StatusCode -lt 200 -or $Response.StatusCode -ge 400) {
                throw "HTTP $($Response.StatusCode)"
            }
            $Succeeded = $true
        } catch {
            if ($Attempt -eq 3) { throw }
            Start-Sleep -Seconds 2
        }
    }
}

Write-Host 'All official model catalog URLs resolved successfully.'
