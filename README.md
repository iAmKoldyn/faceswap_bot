# FaceFusion Automation (Bot + API + Android)

This repo contains:
- Telegram bot that queues FaceFusion jobs.
- FastAPI backend for mobile/remote clients.
- Android MVP client (Kotlin + XML).
- Local FaceFusion 3.5.2 sources.

## Components
- `bot.py` – Telegram bot (private + group modes).
- `backend/` – FastAPI API server.
- `android/` – Android app.
- `facefusion-3.5.2/` – FaceFusion engine.
- `postman_collection.json` – Postman collection.
- `examples/` – sample images/videos for API tests.

## Quick Start (Bot)
1) Install dependencies:
```
pip install -r requirements.txt
```
2) Create `.env` in repo root:
```
TELEGRAM_BOT_TOKEN=your_token
FACEFUSION_DIR=./facefusion-3.5.2
FACEFUSION_VIDEO_EXEC=cuda
FACEFUSION_IMAGE_EXEC=cpu
```
3) Check `facefusion-3.5.2/facefusion.ini` paths.
4) Run:
```
python bot.py
```

## Backend API
Start server:
```
python server.py
```
or
```
uvicorn server:app --host 0.0.0.0 --port 8000
```

### Auth (JWT only)
All endpoints require JWT except `/health`.

Mint token locally:
```
python -m backend.mint_token --user-id demo
```

`.env` must include:
```
JWT_SECRET=your_secret
JWT_ALG=HS256
JWT_REQUIRED=1
```

Use header:
```
Authorization: Bearer <JWT>
```

### API endpoints (core)
- `GET /health`
- `POST /jobs`
- `POST /jobs/{job_id}/source`
- `POST /jobs/{job_id}/target`
- `POST /jobs/{job_id}/submit`
- `GET /jobs/{job_id}`
- `GET /jobs/{job_id}/events` (SSE)
- `GET /jobs/{job_id}/result`
- `POST /jobs/{job_id}/webhook`
- `POST /jobs/quick`

### Postman
Import `postman_collection.json` and set variables:
- `base_url`
- `jwt`
- `source_file`, `target_photo`, `target_video`

## Android App
Open `android/` in Android Studio.

Optional default base URL in `android/local.properties`:
```
FACEFUSION_BASE_URL=http://10.39.78.79:8000
```

Build:
- Debug APK: `android/app/build/outputs/apk/debug/app-debug.apk`
- Release APK: Build -> Generate Signed APK

Notes:
- Emulator uses `http://10.0.2.2:8000` for local host.
- Paste JWT access token in the UI (no Bearer).
- SSE is used for job status updates (no polling).

## ZeroTier (remote access)
Use ZeroTier when the server is on a private network:
1) Join the same ZeroTier network on server and phone.
2) Authorize both devices in the ZeroTier console.
3) Open inbound TCP 8000 on server (Windows firewall).
4) Access API via `http://<zt-ip>:8000`.

If requests hang, disable other VPNs on the server.

## Git Ignore
Sensitive and build artifacts are excluded:
- `.env`, `android/local.properties`, build folders, model caches, outputs.

## Troubleshooting
- If Android cannot connect to local host, use `10.0.2.2` (emulator) or ZeroTier IP (device).
- If uploads fail, ensure file extensions are preserved (jpg/png/mp4/mov).
