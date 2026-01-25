# FaceFusion Mobile MVP (Android)

This is a minimal Android client that talks to the FaceFusion API server.

## Setup
1) Open the `android/` folder in Android Studio.
2) (Optional) Set default Base URL in `android/local.properties`:
```
FACEFUSION_BASE_URL=http://10.39.78.79:8000
```
3) Verify Base URL in the UI (or leave empty and enter manually).
4) Paste a JWT access token into the app (manual minting).

## Run on Samsung
- Enable Developer Options and USB debugging.
- Connect the device and run from Android Studio.

## API flow
The app does:
1) `POST /jobs`
2) upload source + target
3) `POST /jobs/{id}/submit`
4) listen to `GET /jobs/{id}/events` (SSE)
5) download `/jobs/{id}/result`

Note: HTTP is enabled for local/ZeroTier testing via `usesCleartextTraffic=true`.
