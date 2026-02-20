# FaceFusion Automation (Bot + Backend API + Android)

Репозиторий объединяет три рабочих слоя:
- Telegram-бот для запуска FaceFusion из чатов.
- Backend API на FastAPI для мобильного клиента и внешних интеграций.
- Android-клиент (Kotlin/XML) для загрузки source/target и получения результата.

Дополнительно в репозитории лежит локальная копия движка `facefusion-3.5.2`.

## Состав проекта

- `bot.py` — Telegram-бот (private + group сценарии, очереди image/video).
- `backend/` — API-слой и оркестрация джобов FaceFusion.
- `android/` — мобильный клиент Android.
- `facefusion-3.5.2/` — движок FaceFusion CLI/UI.
- `postman_collection.json` — коллекция для тестов API.
- `curl_examples.md`, `examples/` — примеры запросов и тестовых файлов.

## Текущая архитектура

### 1) FaceFusion слой
- Используется CLI-механика FaceFusion через job manager/job runner.
- Конфиг движка: `facefusion-3.5.2/facefusion.ini`.
- Основные режимы, используемые проектом:
  - `photo_video_fast`
  - `photo_video_quality`
  - `photo_photo_gpen`
  - `photo_photo_codeformer`

### 2) Backend API слой (`backend/`)
- FastAPI-приложение: `backend/app.py`.
- JWT middleware включен, публичный без токена только `GET /health`.
- Две очереди в памяти процесса:
  - видео-джобы
  - фото-джобы
- Файловое хранение:
  - `jobs_path/`
  - `source_paths/`
  - `target_path/`
  - `output_path/`
- SSE-стрим статуса: `GET /jobs/{job_id}/events`.

### 3) Telegram-бот слой (`bot.py`)
- Поддержка inline/кнопочного сценария.
- Режимы переключаются командами/кнопками.
- Создает и отправляет FaceFusion job в очередь.
- Отдельные очереди для image/video внутри процесса бота.

### 4) Android слой (`android/`)
- MVVM архитектура (подробно в `android/README.md`).
- Авторизация JWT.
- Источник: selfie/галерея/файлы.
- Цель: фото или видео (зависит от режима).
- Статусы через SSE + fallback на `GET /jobs/{id}`.

## Требования

- Python 3.10+ (рекомендуется 3.12).
- FFmpeg/CURL по требованиям FaceFusion.
- Для GPU-режима — корректный CUDA/cuDNN/onnxruntime стек по документации FaceFusion.
- Android Studio для мобильного клиента.

## Установка зависимостей

### Backend + bot
```bash
pip install -r requirements.txt
```

### FaceFusion
Ставится отдельно в зависимости от выбранного способа (рекомендуется по официальной инструкции FaceFusion).
Локально в репозитории есть `facefusion-3.5.2/requirements.txt`.

## Базовая конфигурация `.env`

```env
TELEGRAM_BOT_TOKEN=...

FACEFUSION_DIR=./facefusion-3.5.2
FACEFUSION_CONFIG=./facefusion-3.5.2/facefusion.ini
FACEFUSION_JOBS=./jobs_path
FACEFUSION_SOURCES=./source_paths
FACEFUSION_TARGETS=./target_path
FACEFUSION_OUTPUTS=./output_path

FACEFUSION_VIDEO_EXEC=cuda
FACEFUSION_IMAGE_EXEC=cpu
FACEFUSION_VIDEO_VMS=strict
FACEFUSION_IMAGE_VMS=strict

JWT_SECRET=...
JWT_ALG=HS256
JWT_REQUIRED=1
```

## Запуск

### Backend API
```bash
python server.py
```
или
```bash
uvicorn backend.app:app --host 0.0.0.0 --port 8000
```

### Telegram-бот
```bash
python bot.py
```

### Android
См. пошагово в `android/README.md`.

## JWT и API

Токен генерируется локально через утилиту:
```bash
python -m backend.mint_token --user-id demo
```

Использование в запросах:
```http
Authorization: Bearer <JWT>
```

### Основные endpoint'ы
- `GET /health`
- `GET /auth/verify`
- `POST /jobs`
- `POST /jobs/{job_id}/source`
- `POST /jobs/{job_id}/target`
- `POST /jobs/{job_id}/submit`
- `POST /jobs/{job_id}/webhook`
- `POST /jobs/{job_id}/cancel`
- `POST /jobs/quick`
- `GET /jobs/{job_id}`
- `GET /jobs/{job_id}/events`
- `GET /jobs/{job_id}/result`

## Ограничения текущей реализации

- Очереди backend и бота процесс-локальные (in-memory), без внешнего брокера.
- Хранилище результатов и джобов локальное (filesystem), без распределенного storage.
- Видео-лимиты backend не для UI:
  - размер до `60 MB`
  - длительность до `120 сек`

## API

- Postman: `postman_collection.json`
- cURL примеры: `curl_examples.md`

## Подключение к лабе

Для доступа с мобильных устройств используется ZeroTier.
Пример базового URL для устройства:
```text
http://<zerotier-ip-сервера>:8000
```

---

## Roadmap-а

### Этап 1. Nginx как edge-прослойка перед backend API
Цель: поставить единый вход между внешними сервисами и FastAPI.

- Reverse proxy `nginx - backend`.
- TLS termination (HTTPS).
- Rate limit и базовый anti-abuse.
- CORS и безопасные заголовки.
- Корректный proxy для SSE (`/jobs/{id}/events`).

### Этап 2. Балансировка нагрузки backend
Цель: масштабировать API-инстансы горизонтально.

- `upstream` с несколькими backend pod/instance.
- Отделение API от worker-процесса в отдельные сервисы.

### Этап 3. Обертка в Kubernetes

3 и 4 пункты как в [`nstart-devops-test`](https://gitlab.com/gribochki1/nstart-devops-test)

Цель: перевод ручного запуска в k8s.

- Deployment для API.
- Deployment/StatefulSet для workers.
- PVC для общих директорий `jobs/source/target/output`.
- ConfigMap/Secret для env и токенов.
- Service + Ingress.

### Этап 4. Helm chart
Цель: воспроизводимый деплой и параметризация окружений.

- Helm chart для API + workers + ingress.
- `values.yaml` для профилей (dev/stage/prod).
- Параметры GPU/CPU воркеров, limits/requests.

### Этап 5. Репликация и отказоустойчивость
Цель: повысить надежность и масштабируемость.

- Реплики API за балансировщиком.
- Вынос очередей в внешний брокер (RabbitMQ) вместо in-memory.
- Вынос артефактов в объектное хранилище (MinIO) вместо локального диска.
- Репликация/резервирование БД метаданных (при добавлении БД).
- Метрики с [`react-monitoring-demo`](https://github.com/iAmKoldyn/react-monitoring-demo).
