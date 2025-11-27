param(
    [string]$content
)

Write-Host "CONTENT RECEIVED: '$content'"

$hookUrl = "https://discord.com/api/webhooks/1443237448300626043/LmWHrXONWI7PzqRnbSmn2SS0AM11LAfesK7MsOo1FedN9NKlGNLL7umBpApUF_1Fq_9A"

$payload = [PSCustomObject]@{
    content = $content
}

Invoke-RestMethod `
    -Uri $hookUrl `
    -Method Post `
    -Body ($payload | ConvertTo-Json -Depth 4) `
    -ContentType "application/json; charset=utf-8"
