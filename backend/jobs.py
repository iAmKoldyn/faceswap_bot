import asyncio
import json
import subprocess
import threading
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Literal, Optional, Tuple

import httpx
from fastapi import HTTPException, UploadFile

from .config import (
    ALLOWED_IMAGE_EXT,
    ALLOWED_VIDEO_EXT,
    API_JOBS_PATH,
    CONFIG_PATH,
    FACEFUSION_DIR,
    IMAGE_EXEC_PROVIDERS,
    IMAGE_VMS,
    JOBS_PATH,
    MAX_VIDEO_SECONDS,
    MAX_VIDEO_SIZE_BYTES,
    OUTPUT_DIR,
    PROGRESS_RE,
    SOURCE_DIR,
    TARGET_DIR,
    VIDEO_EXEC_PROVIDERS,
    VIDEO_VMS,
)


JOB_LOCK = threading.Lock()


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def job_path(job_id: str) -> Path:
    return API_JOBS_PATH / f"{job_id}.json"


def load_job(job_id: str) -> Dict[str, Any]:
    with JOB_LOCK:
        path = job_path(job_id)
        if not path.exists():
            raise HTTPException(status_code=404, detail="Job not found")
        return json.loads(path.read_text(encoding="utf-8"))


def save_job(job: Dict[str, Any]) -> None:
    with JOB_LOCK:
        job["updated_at"] = now_iso()
        job_path(job["job_id"]).write_text(json.dumps(job, ensure_ascii=False, indent=2), encoding="utf-8")


def update_job(job_id: str, **fields: Any) -> Dict[str, Any]:
    job = load_job(job_id)
    job.update(fields)
    save_job(job)
    return job


def allowed_target_kind(mode: str) -> Literal["image", "video"]:
    return "video" if mode.startswith("photo_video") else "image"


def resolve_models(mode: str, target: Path) -> Tuple[str, str]:
    if mode == "photo_video_fast":
        return "inswapper_128_fp16", "gfpgan_1.4"
    if mode == "photo_video_quality":
        return "hyperswap_1c_256", "codeformer"
    if mode == "photo_photo_gpen":
        return "hyperswap_1c_256", "gpen_bfr_1024"
    if mode == "photo_photo_codeformer":
        return "hyperswap_1c_256", "codeformer"
    if target.suffix.lower() in ALLOWED_VIDEO_EXT:
        return "inswapper_128_fp16", "gfpgan_1.4"
    return "hyperswap_1c_256", "gpen_bfr_1024"


def build_output_path(target_path: Path) -> Path:
    ext = target_path.suffix or ".mp4"
    return OUTPUT_DIR / f"{uuid.uuid4().hex}{ext}"


async def save_upload_file(upload: UploadFile, dest: Path, max_bytes: Optional[int] = None) -> int:
    size = 0
    dest.parent.mkdir(parents=True, exist_ok=True)
    with dest.open("wb") as f:
        while True:
            chunk = await upload.read(1024 * 1024)
            if not chunk:
                break
            size += len(chunk)
            if max_bytes and size > max_bytes:
                f.close()
                dest.unlink(missing_ok=True)
                raise HTTPException(status_code=413, detail="File too large")
            f.write(chunk)
    await upload.close()
    return size


def is_video_too_long(video_path: Path, max_seconds: int = MAX_VIDEO_SECONDS) -> bool:
    try:
        import cv2

        cap = cv2.VideoCapture(str(video_path))
        fps = cap.get(cv2.CAP_PROP_FPS) or 0
        frames = cap.get(cv2.CAP_PROP_FRAME_COUNT) or 0
        cap.release()
        if fps > 0:
            return (frames / fps) > max_seconds
    except Exception:
        pass
    return False


def run_cli(args: list[str]) -> None:
    subprocess.run(args, cwd=FACEFUSION_DIR, check=True)


def enqueue_facefusion_job(job: Dict[str, Any]) -> None:
    job_id = job["job_id"]
    source = Path(job["source_path"])
    target = Path(job["target_path"])
    output = Path(job["output_path"])
    swap_model, enh_model = resolve_models(job["mode"], target)

    run_cli(["python", "facefusion.py", "job-create", job_id, "--jobs-path", str(JOBS_PATH)])

    args = [
        "python",
        "facefusion.py",
        "job-add-step",
        job_id,
        "-s",
        str(source),
        "-t",
        str(target),
        "-o",
        str(output),
        "--face-swapper-model",
        swap_model,
        "--face-enhancer-model",
        enh_model,
        "--jobs-path",
        str(JOBS_PATH),
        "--config-path",
        str(CONFIG_PATH),
    ]
    if job.get("reference_frame_number"):
        args += ["--reference-frame-number", str(job["reference_frame_number"])]
    run_cli(args)

    run_cli(["python", "facefusion.py", "job-submit", job_id, "--jobs-path", str(JOBS_PATH)])


async def send_webhook(job: Dict[str, Any], event: str) -> None:
    webhook_url = job.get("webhook_url")
    if not webhook_url:
        return
    events = job.get("webhook_events") or ["queued", "running", "completed", "failed"]
    if event not in events:
        return
    payload = {
        "event": event,
        "job_id": job["job_id"],
        "status": job["status"],
        "stage": job.get("stage"),
        "progress": job.get("progress", 0),
        "mode": job["mode"],
        "target_kind": job["target_kind"],
        "owner_id": job.get("owner_id"),
        "updated_at": job.get("updated_at"),
    }
    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            await client.post(webhook_url, json=payload)
    except Exception:
        job["webhook_last_error"] = "failed"
        save_job(job)


def parse_progress(line: str) -> Optional[Tuple[str, int]]:
    match = PROGRESS_RE.search(line)
    if not match:
        return None
    stage = match.group(1)
    percent = int(match.group(2))
    return stage, percent


def run_facefusion_job(job_id: str, target_kind: Literal["image", "video"]) -> None:
    args = [
        "python",
        "facefusion.py",
        "job-run",
        job_id,
        "--jobs-path",
        str(JOBS_PATH),
        "--config-path",
        str(CONFIG_PATH),
    ]
    exec_providers = VIDEO_EXEC_PROVIDERS if target_kind == "video" else IMAGE_EXEC_PROVIDERS
    vms = VIDEO_VMS if target_kind == "video" else IMAGE_VMS
    if exec_providers:
        args += ["--execution-providers", *exec_providers]
    if vms:
        args += ["--video-memory-strategy", vms]

    proc = subprocess.Popen(
        args,
        cwd=FACEFUSION_DIR,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    )
    if proc.stdout:
        for raw_line in proc.stdout:
            line = raw_line.strip()
            if not line:
                continue
            parsed = parse_progress(line)
            if parsed:
                stage, percent = parsed
                update_job(job_id, stage=stage, progress=percent)
    ret = proc.wait()
    if ret != 0:
        raise subprocess.CalledProcessError(ret, args)


async def start_workers(app) -> None:
    app.state.queue_video = asyncio.Queue()
    app.state.queue_image = asyncio.Queue()
    app.state.running_video = False
    app.state.running_image = False

    async def worker(kind: Literal["image", "video"]) -> None:
        queue = app.state.queue_video if kind == "video" else app.state.queue_image
        running_key = "running_video" if kind == "video" else "running_image"
        while True:
            job_id = await queue.get()
            job = load_job(job_id)
            app.state.__dict__[running_key] = True
            try:
                job["status"] = "running"
                job["stage"] = None
                job["progress"] = 0
                save_job(job)
                await send_webhook(job, "running")
                loop = asyncio.get_running_loop()
                await loop.run_in_executor(None, run_facefusion_job, job_id, job["target_kind"])
                job["status"] = "completed"
                job["stage"] = "completed"
                job["progress"] = 100
                save_job(job)
                await send_webhook(job, "completed")
            except Exception as exc:
                job["status"] = "failed"
                job["stage"] = "failed"
                job["error"] = str(exc)
                save_job(job)
                await send_webhook(job, "failed")
            finally:
                queue.task_done()
                app.state.__dict__[running_key] = False

    asyncio.create_task(worker("video"))
    asyncio.create_task(worker("image"))


def queue_job(app, job_id: str, target_kind: Literal["image", "video"]) -> None:
    queue = app.state.queue_video if target_kind == "video" else app.state.queue_image
    queue.put_nowait(job_id)


def create_job_record(owner_id: str, mode: str) -> Dict[str, Any]:
    target_kind = allowed_target_kind(mode)
    job_id = f"api-{uuid.uuid4().hex[:8]}"
    job = {
        "job_id": job_id,
        "owner_id": owner_id,
        "mode": mode,
        "target_kind": target_kind,
        "status": "waiting_source",
        "source_path": None,
        "target_path": None,
        "output_path": None,
        "reference_frame_number": None,
        "progress": 0,
        "stage": None,
        "webhook_url": None,
        "webhook_events": None,
        "created_at": now_iso(),
        "updated_at": now_iso(),
    }
    save_job(job)
    return job
