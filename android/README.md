# FaceFusion Mobile (Android)

Android client for FaceFusion backend API (`server.py` / `backend/`).

## Implemented Features
- Modes:
  - `photo_video_fast`
  - `photo_video_quality`
  - `photo_photo_gpen`
  - `photo_photo_codeformer`
- JWT auth (`Authorization: Bearer <token>`)
- Live job status via SSE (`GET /jobs/{job_id}/events`) with watchdog fallback (`GET /jobs/{job_id}`)
- Source actions:
  - Selfie (camera)
  - Pick source photo from gallery
  - Pick source photo from files
- Target actions:
  - Pick target photo/video from gallery (depends on mode)
  - Pick target photo/video from files (depends on mode)
- Source/target previews
- Save output image/video to gallery
- Health indicator (`GET /health`)

## Architecture (MVVM)

The app is now split into UI/Data/Domain layers.

- UI:
  - `android/app/src/main/java/com/facefusion/app/MainActivity.kt`
  - `android/app/src/main/java/com/facefusion/app/ui/main/MainUiState.kt`
  - `android/app/src/main/java/com/facefusion/app/ui/main/MainUiAction.kt`
  - `android/app/src/main/java/com/facefusion/app/ui/main/MainUiEffect.kt`
  - `android/app/src/main/java/com/facefusion/app/ui/main/MainViewModel.kt`
- Data:
  - `android/app/src/main/java/com/facefusion/app/data/network/*`
  - `android/app/src/main/java/com/facefusion/app/data/repository/*`
  - `android/app/src/main/java/com/facefusion/app/data/local/SettingsDataStore.kt`
  - `android/app/src/main/java/com/facefusion/app/data/media/MediaManager.kt`
- Domain:
  - `android/app/src/main/java/com/facefusion/app/domain/result/AppResult.kt`

### Responsibilities
- `MainActivity`: only renders UI and dispatches `MainUiAction` to `MainViewModel`.
- `MainViewModel`: job orchestration, health monitor, auth verify, state/effects.
- `FaceFusionRepositoryImpl`: REST + SSE and network error mapping.
- `MediaManager`: URI validation, cache copy, MediaStore save.
- `SettingsDataStore`: persists `baseUrl` and `token`.

## API Contract Used by Android
- `GET /health`
- `GET /auth/verify`
- `POST /jobs`
- `POST /jobs/{job_id}/source`
- `POST /jobs/{job_id}/target`
- `POST /jobs/{job_id}/submit`
- `GET /jobs/{job_id}`
- `GET /jobs/{job_id}/events`
- `GET /jobs/{job_id}/result`

## Setup
1. Open `android/` in Android Studio.
2. Optional default base URL in `android/local.properties`:

```properties
FACEFUSION_BASE_URL=http://10.39.78.79:8000
```

3. Run backend server (`python server.py`).
4. Paste JWT token in app auth field.

## Connectivity Notes
- Emulator -> local backend: `http://10.0.2.2:8000`
- Physical device (ZeroTier): `http://<zerotier_server_ip>:8000`
- `AndroidManifest.xml` has `usesCleartextTraffic=true` for local HTTP.

## Build APK
In Android Studio:
1. `Build > Build APK(s)` for debug APK
2. `Build > Generate Signed Bundle / APK` for release

Default debug APK path:
- `android/app/build/outputs/apk/debug/app-debug.apk`

## Gradle Dependencies Added
See `android/app/build.gradle`:
- `androidx.lifecycle:lifecycle-viewmodel-ktx`
- `androidx.datastore:datastore-preferences`
- `retrofit2`, `okhttp3`, coroutines

## Troubleshooting
- `401 Unauthorized`: validate JWT and backend auth config (`JWT_SECRET`, `JWT_REQUIRED`).
- Emulator cannot reach localhost: use `10.0.2.2` instead of `127.0.0.1`.
- Device cannot reach server: check firewall (port `8000`), VPN conflicts, ZeroTier status.
- SSE disconnects: app auto-reconnects and watchdog polling continues status tracking.
