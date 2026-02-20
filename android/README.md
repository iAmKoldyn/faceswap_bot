# Android клиент FaceFusion

Android-приложение для работы с backend API (`backend/app.py`).

## Что уже реализовано

- Режимы обработки:
  - `photo_video_fast`
  - `photo_video_quality`
  - `photo_photo_gpen`
  - `photo_photo_codeformer`
- Авторизация через JWT(выдаетася админом лабы) (`Authorization: Bearer <token>`).
- Загрузка source:
  - selfie (камера)
  - фото из галереи
  - фото из файлов
- Загрузка target:
  - фото/видео из галереи (зависит от режима)
  - фото/видео из файлов (зависит от режима)
- Превью source/target.
- Получение статусов через SSE + fallback проверка статуса.
- Скачивание результата и сохранение image/video в галерею.
- Индикация `health` backend.

## Архитектура (MVVM)

Проект разнесен на UI / Data / Domain.

### UI
- `android/app/src/main/java/com/facefusion/app/MainActivity.kt`
- `android/app/src/main/java/com/facefusion/app/ui/main/MainUiState.kt`
- `android/app/src/main/java/com/facefusion/app/ui/main/MainUiAction.kt`
- `android/app/src/main/java/com/facefusion/app/ui/main/MainUiEffect.kt`
- `android/app/src/main/java/com/facefusion/app/ui/main/MainViewModel.kt`

### Data
- `android/app/src/main/java/com/facefusion/app/data/network/*`
- `android/app/src/main/java/com/facefusion/app/data/repository/*`
- `android/app/src/main/java/com/facefusion/app/data/local/SettingsDataStore.kt`
- `android/app/src/main/java/com/facefusion/app/data/media/MediaManager.kt`

### Domain
- `android/app/src/main/java/com/facefusion/app/domain/result/AppResult.kt`

## Роли компонентов

- `MainActivity`:
  - только рендер UI
  - отправка `MainUiAction` в `MainViewModel`
- `MainViewModel`:
  - сценарий create/upload/submit/observe/download
  - auth verify
  - health monitor
  - управление `UiState` / `UiEffect`
- `FaceFusionRepositoryImpl`:
  - REST-запросы и SSE-подключение
  - унифицированная обработка ошибок (`AppResult`)
- `MediaManager`:
  - валидация типов файлов
  - копирование URI - cache file
  - сохранение результата в `MediaStore`
- `SettingsDataStore`:
  - хранение `baseUrl` и `token`

## Endpoint'ы, которые использует Android

- `GET /health`
- `GET /auth/verify`
- `POST /jobs`
- `POST /jobs/{job_id}/source`
- `POST /jobs/{job_id}/target`
- `POST /jobs/{job_id}/submit`
- `GET /jobs/{job_id}`
- `GET /jobs/{job_id}/events`
- `GET /jobs/{job_id}/result`

## Настройка

1. Откройте папку `android/` в Android Studio.
2. Опционально укажите default base URL в `android/local.properties`:

```properties
FACEFUSION_BASE_URL=http://10.39.78.79:8000
```

3. Запустите backend (`python server.py`).
4. Вставьте JWT-токен в поле авторизации приложения.

## Подключение к backend


ZeroTier:
`http://<ip_сервера>:8000`

В манифесте включен `usesCleartextTraffic=true` для локальных HTTP-сценариев.

## Сборка APK

Через Android Studio:
1. `Build > Build APK(s)` — debug APK
2. `Build > Generate Signed Bundle / APK` — release

Путь debug APK:
- `android/app/build/outputs/apk/debug/app-debug.apk`

## Dependencies

См. `android/app/build.gradle`:
- `androidx.lifecycle:lifecycle-viewmodel-ktx`
- `androidx.datastore:datastore-preferences`
- `retrofit2` + `okhttp3`
- Kotlin coroutines

## Troubleshooting

- `401 Unauthorized`:
  - проверьте токен
  - проверьте backend JWT-конфиг (`JWT_SECRET`, `JWT_REQUIRED`)
- Эмулятор не видит localhost:
  - используйте `10.0.2.2`, не `127.0.0.1`
- Устройство не подключается:
  - проверьте firewall (порт `8000`)
  - проверьте ZeroTier
- SSE нестабилен:
  - приложение автоматически переподключается
  - watchdog-проверка статуса остается рабочей
