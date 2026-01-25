import asyncio
import json
from pathlib import Path
from typing import Dict, Optional
import uuid

from fastapi import Depends, FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import FileResponse, JSONResponse, StreamingResponse
from pydantic import BaseModel

from .auth import get_current_user, verify_token
from .config import (
    ALLOWED_IMAGE_EXT,
    ALLOWED_VIDEO_EXT,
    DEFAULT_MODE,
    JWT_REQUIRED,
    JWT_SECRET,
    MAX_VIDEO_SIZE_BYTES,
    SOURCE_DIR,
    TARGET_DIR,
)
from .jobs import (
    build_output_path,
    create_job_record,
    enqueue_facefusion_job,
    is_video_too_long,
    load_job,
    queue_job,
    save_job,
    save_upload_file,
    send_webhook,
    start_workers,
)


ALLOWED_MODES = {
    "photo_video_fast",
    "photo_video_quality",
    "photo_photo_gpen",
    "photo_photo_codeformer",
}


class JobCreateRequest(BaseModel):
    mode: str = DEFAULT_MODE


class JobResponse(BaseModel):
    job_id: str
    status: str
    mode: str
    target_kind: str
    owner_id: str
    progress: int
    stage: Optional[str]
    source_uploaded: bool
    target_uploaded: bool
    result_ready: bool


class WebhookRequest(BaseModel):
    url: str
    events: Optional[list[str]] = None


def assert_owner(job: Dict[str, str], user_id: str) -> None:
    if job.get("owner_id") != user_id:
        raise HTTPException(status_code=403, detail="Forbidden")


def job_to_response(job: Dict[str, str]) -> JobResponse:
    return JobResponse(
        job_id=job["job_id"],
        status=job["status"],
        mode=job["mode"],
        target_kind=job["target_kind"],
        owner_id=job["owner_id"],
        progress=job.get("progress", 0),
        stage=job.get("stage"),
        source_uploaded=bool(job.get("source_path")),
        target_uploaded=bool(job.get("target_path")),
        result_ready=job["status"] == "completed" and bool(job.get("output_path")),
    )


app = FastAPI(title="FaceFusion API", version="1.0.0")


@app.middleware("http")
async def require_jwt_for_post(request: Request, call_next):
    if JWT_REQUIRED and request.url.path != "/health":
        try:
            request.state.user_id = verify_token(request.headers.get("Authorization"))
        except HTTPException as exc:
            return JSONResponse(status_code=exc.status_code, content={"detail": exc.detail})
    return await call_next(request)


@app.on_event("startup")
async def startup() -> None:
    if JWT_REQUIRED and not JWT_SECRET:
        raise RuntimeError("JWT_SECRET is required when JWT_REQUIRED=1")
    await start_workers(app)


@app.get("/health")
async def health() -> Dict[str, str]:
    return {"status": "ok"}


@app.post("/jobs", response_model=JobResponse)
async def create_job(payload: JobCreateRequest, user_id: str = Depends(get_current_user)) -> JobResponse:
    mode = payload.mode if payload.mode in ALLOWED_MODES else DEFAULT_MODE
    job = create_job_record(user_id, mode)
    return job_to_response(job)


@app.post("/jobs/{job_id}/source", response_model=JobResponse)
async def upload_source(
    job_id: str,
    file: UploadFile = File(...),
    user_id: str = Depends(get_current_user),
) -> JobResponse:
    job = load_job(job_id)
    assert_owner(job, user_id)
    suffix = Path(file.filename or "").suffix.lower()
    if suffix not in ALLOWED_IMAGE_EXT:
        raise HTTPException(status_code=400, detail="Source must be jpg/jpeg/png")
    dest = SOURCE_DIR / f"{uuid.uuid4().hex}{suffix}"
    await save_upload_file(file, dest)
    job["source_path"] = str(dest)
    job["status"] = "waiting_target"
    save_job(job)
    return job_to_response(job)


@app.post("/jobs/{job_id}/target", response_model=JobResponse)
async def upload_target(
    job_id: str,
    file: UploadFile = File(...),
    user_id: str = Depends(get_current_user),
) -> JobResponse:
    job = load_job(job_id)
    assert_owner(job, user_id)
    suffix = Path(file.filename or "").suffix.lower()
    if suffix in ALLOWED_IMAGE_EXT:
        target_kind = "image"
    elif suffix in ALLOWED_VIDEO_EXT:
        target_kind = "video"
    else:
        raise HTTPException(status_code=400, detail="Target must be jpg/jpeg/png or mp4/mov")

    if target_kind != job["target_kind"]:
        raise HTTPException(status_code=400, detail=f"Target type must be {job['target_kind']}")

    dest = TARGET_DIR / f"{uuid.uuid4().hex}{suffix}"
    max_bytes = MAX_VIDEO_SIZE_BYTES if target_kind == "video" else None
    await save_upload_file(file, dest, max_bytes=max_bytes)
    if target_kind == "video" and is_video_too_long(dest):
        dest.unlink(missing_ok=True)
        raise HTTPException(status_code=400, detail="Video too long (max 2 minutes)")

    job["target_path"] = str(dest)
    if job.get("source_path"):
        job["status"] = "ready"
    save_job(job)
    return job_to_response(job)


@app.post("/jobs/{job_id}/submit", response_model=JobResponse)
async def submit_job(
    job_id: str,
    reference_frame_number: Optional[int] = Form(default=None),
    user_id: str = Depends(get_current_user),
) -> JobResponse:
    job = load_job(job_id)
    assert_owner(job, user_id)
    if not job.get("source_path") or not job.get("target_path"):
        raise HTTPException(status_code=400, detail="Source and target are required")
    job["output_path"] = str(build_output_path(Path(job["target_path"])))
    if reference_frame_number is not None:
        job["reference_frame_number"] = reference_frame_number
    save_job(job)
    enqueue_facefusion_job(job)

    queue_job(app, job_id, job["target_kind"])
    job["status"] = "queued"
    job["stage"] = "queued"
    job["progress"] = 0
    save_job(job)
    await send_webhook(job, "queued")
    return job_to_response(job)


@app.post("/jobs/{job_id}/webhook", response_model=JobResponse)
async def set_webhook(
    job_id: str,
    payload: WebhookRequest,
    user_id: str = Depends(get_current_user),
) -> JobResponse:
    job = load_job(job_id)
    assert_owner(job, user_id)
    if not payload.url.startswith("http://") and not payload.url.startswith("https://"):
        raise HTTPException(status_code=400, detail="Webhook url must be http(s)")
    job["webhook_url"] = payload.url
    job["webhook_events"] = payload.events or ["queued", "running", "completed", "failed"]
    save_job(job)
    return job_to_response(job)


@app.post("/jobs/quick", response_model=JobResponse)
async def quick_job(
    mode: str = Form(default=DEFAULT_MODE),
    source: UploadFile = File(...),
    target: UploadFile = File(...),
    user_id: str = Depends(get_current_user),
) -> JobResponse:
    job_mode = mode if mode in ALLOWED_MODES else DEFAULT_MODE
    job = create_job_record(user_id, job_mode)
    job_id = job["job_id"]

    await upload_source(job_id, source, user_id=user_id)
    await upload_target(job_id, target, user_id=user_id)
    return await submit_job(job_id, reference_frame_number=None, user_id=user_id)


@app.get("/jobs/{job_id}", response_model=JobResponse)
async def get_job(job_id: str, user_id: str = Depends(get_current_user)) -> JobResponse:
    job = load_job(job_id)
    assert_owner(job, user_id)
    return job_to_response(job)

@app.get("/jobs/{job_id}/events")
async def job_events(job_id: str, request: Request, user_id: str = Depends(get_current_user)):
    job = load_job(job_id)
    assert_owner(job, user_id)

    async def stream():
        last_payload = None
        while True:
            if await request.is_disconnected():
                break
            job_state = load_job(job_id)
            payload = job_to_response(job_state).dict()
            data = json.dumps(payload, ensure_ascii=False)
            if data != last_payload:
                yield f"data: {data}\n\n"
                last_payload = data
                if job_state.get("status") in {"completed", "failed", "cancelled"}:
                    break
            await asyncio.sleep(1)

    headers = {
        "Cache-Control": "no-cache",
        "Connection": "keep-alive",
    }
    return StreamingResponse(stream(), media_type="text/event-stream", headers=headers)


@app.get("/jobs/{job_id}/result")
async def get_result(job_id: str, user_id: str = Depends(get_current_user)):
    job = load_job(job_id)
    assert_owner(job, user_id)
    if job["status"] != "completed" or not job.get("output_path"):
        raise HTTPException(status_code=404, detail="Result not ready")
    path = Path(job["output_path"])
    if not path.exists():
        raise HTTPException(status_code=404, detail="Result not found")
    return FileResponse(path)


@app.post("/jobs/{job_id}/cancel", response_model=JobResponse)
async def cancel_job(job_id: str, user_id: str = Depends(get_current_user)) -> JobResponse:
    job = load_job(job_id)
    assert_owner(job, user_id)
    job["status"] = "cancelled"
    save_job(job)
    return job_to_response(job)
