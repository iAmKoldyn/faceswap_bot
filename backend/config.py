import os
import re
from pathlib import Path


ENV_PATH = Path(".env")


def load_env_from_file() -> None:
    if not ENV_PATH.exists():
        return
    for raw_line in ENV_PATH.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        key = key.strip()
        value = value.strip().strip('"').strip("'")
        if key:
            os.environ[key] = value


load_env_from_file()

BASE_DIR = Path(__file__).resolve().parents[1]
FACEFUSION_DIR = Path(os.environ.get("FACEFUSION_DIR", BASE_DIR / "facefusion-3.5.2")).resolve()
CONFIG_PATH = Path(os.environ.get("FACEFUSION_CONFIG", FACEFUSION_DIR / "facefusion.ini")).resolve()
JOBS_PATH = Path(os.environ.get("FACEFUSION_JOBS", BASE_DIR / "jobs_path")).resolve()
SOURCE_DIR = Path(os.environ.get("FACEFUSION_SOURCES", BASE_DIR / "source_paths")).resolve()
TARGET_DIR = Path(os.environ.get("FACEFUSION_TARGETS", BASE_DIR / "target_path")).resolve()
OUTPUT_DIR = Path(os.environ.get("FACEFUSION_OUTPUTS", BASE_DIR / "output_path")).resolve()
API_JOBS_PATH = Path(os.environ.get("API_JOBS_PATH", JOBS_PATH)).resolve()

VIDEO_EXEC_PROVIDERS = os.environ.get("FACEFUSION_VIDEO_EXEC", "cuda").split()
IMAGE_EXEC_PROVIDERS = os.environ.get("FACEFUSION_IMAGE_EXEC", "cpu").split()
VIDEO_VMS = os.environ.get("FACEFUSION_VIDEO_VMS", "strict")
IMAGE_VMS = os.environ.get("FACEFUSION_IMAGE_VMS", "strict")

DEFAULT_MODE = "photo_video_fast"

JWT_SECRET = os.environ.get("JWT_SECRET")
JWT_ALG = os.environ.get("JWT_ALG", "HS256")
JWT_REQUIRED = os.environ.get("JWT_REQUIRED", "1") != "0"
AUTH_API_KEY = os.environ.get("AUTH_API_KEY")

ALLOWED_IMAGE_EXT = {".jpg", ".jpeg", ".png"}
ALLOWED_VIDEO_EXT = {".mp4", ".mov"}
MAX_VIDEO_SIZE_BYTES = 60 * 1024 * 1024
MAX_VIDEO_SECONDS = 120

PROGRESS_RE = re.compile(r"(analysing|extracting|processing|merging|restoring|finalizing):\s+(\d+)%")

for folder in (JOBS_PATH, SOURCE_DIR, TARGET_DIR, OUTPUT_DIR, API_JOBS_PATH):
    folder.mkdir(parents=True, exist_ok=True)
