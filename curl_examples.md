## FaceFusion API curl examples (JWT)

Assumes the server runs at `http://127.0.0.1:8000` and example files exist in `examples/`.
Replace `API_KEY` and `JWT_TOKEN` with your values.

### PowerShell (use curl.exe)

Get JWT token:
```powershell
$token = curl.exe -s -X POST http://127.0.0.1:8000/auth/token `
  -H "Content-Type: application/json" `
  -H "X-API-Key: API_KEY" `
  -d "{\"user_id\":\"demo\"}" | ConvertFrom-Json
$JWT_TOKEN = $token.access_token
```

Create job (photo->photo, gpen):
```powershell
$resp = curl.exe -s -X POST http://127.0.0.1:8000/jobs `
  -H "Content-Type: application/json" `
  -H "Authorization: Bearer $JWT_TOKEN" `
  -d "{\"mode\":\"photo_photo_gpen\"}" | ConvertFrom-Json
$JOB_ID = $resp.job_id
```

Upload source (photo):
```powershell
curl.exe -s -X POST -H "Authorization: Bearer $JWT_TOKEN" -F "file=@examples\\bagrat.jpg" `
  http://127.0.0.1:8000/jobs/$JOB_ID/source
```

Upload target (photo):
```powershell
curl.exe -s -X POST -H "Authorization: Bearer $JWT_TOKEN" -F "file=@examples\\naur.jpg" `
  http://127.0.0.1:8000/jobs/$JOB_ID/target
```

Submit job:
```powershell
curl.exe -s -X POST -H "Authorization: Bearer $JWT_TOKEN" `
  http://127.0.0.1:8000/jobs/$JOB_ID/submit
```

Check status:
```powershell
curl.exe -s -H "Authorization: Bearer $JWT_TOKEN" http://127.0.0.1:8000/jobs/$JOB_ID
```

Download result (jpg):
```powershell
curl.exe -L -H "Authorization: Bearer $JWT_TOKEN" -o result.jpg `
  http://127.0.0.1:8000/jobs/$JOB_ID/result
```

Photo->video (fast):
```powershell
$resp = curl.exe -s -X POST http://127.0.0.1:8000/jobs `
  -H "Content-Type: application/json" `
  -H "Authorization: Bearer $JWT_TOKEN" `
  -d "{\"mode\":\"photo_video_fast\"}" | ConvertFrom-Json
$JOB_ID = $resp.job_id

curl.exe -s -X POST -H "Authorization: Bearer $JWT_TOKEN" -F "file=@examples\\bagrat.jpg" `
  http://127.0.0.1:8000/jobs/$JOB_ID/source

curl.exe -s -X POST -H "Authorization: Bearer $JWT_TOKEN" -F "file=@examples\\video.mp4" `
  http://127.0.0.1:8000/jobs/$JOB_ID/target

curl.exe -s -X POST -H "Authorization: Bearer $JWT_TOKEN" `
  http://127.0.0.1:8000/jobs/$JOB_ID/submit
```

Quick job (one request):
```powershell
curl.exe -s -X POST http://127.0.0.1:8000/jobs/quick `
  -H "Authorization: Bearer $JWT_TOKEN" `
  -F "mode=photo_photo_gpen" `
  -F "source=@examples\\bagrat.jpg" `
  -F "target=@examples\\naur.jpg"
```

### Linux/macOS (curl)

Get JWT token:
```bash
JWT_TOKEN=$(curl -s -X POST http://127.0.0.1:8000/auth/token \
  -H "Content-Type: application/json" \
  -H "X-API-Key: API_KEY" \
  -d '{"user_id":"demo"}' | python -c "import sys, json; print(json.load(sys.stdin)['access_token'])")
```

Create job:
```bash
JOB_ID=$(curl -s -X POST http://127.0.0.1:8000/jobs \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -d '{"mode":"photo_photo_gpen"}' | python -c "import sys, json; print(json.load(sys.stdin)['job_id'])")
```

Upload source:
```bash
curl -s -X POST -H "Authorization: Bearer $JWT_TOKEN" -F "file=@examples/bagrat.jpg" \
  http://127.0.0.1:8000/jobs/$JOB_ID/source
```

Upload target:
```bash
curl -s -X POST -H "Authorization: Bearer $JWT_TOKEN" -F "file=@examples/naur.jpg" \
  http://127.0.0.1:8000/jobs/$JOB_ID/target
```

Submit:
```bash
curl -s -X POST -H "Authorization: Bearer $JWT_TOKEN" http://127.0.0.1:8000/jobs/$JOB_ID/submit
```

Status:
```bash
curl -s -H "Authorization: Bearer $JWT_TOKEN" http://127.0.0.1:8000/jobs/$JOB_ID
```

Download:
```bash
curl -L -H "Authorization: Bearer $JWT_TOKEN" -o result.jpg \
  http://127.0.0.1:8000/jobs/$JOB_ID/result
```

Quick job:
```bash
curl -s -X POST http://127.0.0.1:8000/jobs/quick \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -F "mode=photo_photo_gpen" \
  -F "source=@examples/bagrat.jpg" \
  -F "target=@examples/naur.jpg"
```
