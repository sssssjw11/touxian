param(
  [switch]$Install,
  [int]$ApiPort = 8765,
  [int]$WebPort = 5173
)

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

if ($Install) {
  npm install
  if (-not (Test-Path "..\.venv\Scripts\python.exe")) { python -m venv "..\.venv" }
  & "..\.venv\Scripts\python.exe" -m pip install -r server\requirements.txt
}

$python = if (Test-Path "..\.venv\Scripts\python.exe") { "..\.venv\Scripts\python.exe" } else { "python" }
$env:ATTENTION_API_PORT = "$ApiPort"
$env:ATTENTION_WEB_PORT = "$WebPort"
Start-Process -FilePath $python -ArgumentList "-m", "uvicorn", "server.main:app", "--host", "127.0.0.1", "--port", "$ApiPort" -WorkingDirectory $root -WindowStyle Hidden
npm run dev -- --host 127.0.0.1 --port $WebPort
