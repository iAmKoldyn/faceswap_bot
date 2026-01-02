import asyncio
import logging
import os
import subprocess
import uuid
from pathlib import Path
from typing import Any, Dict, Literal, Optional, Tuple

from telegram import (
    BotCommand,
    File,
    InlineKeyboardButton,
    InlineKeyboardMarkup,
    Message,
    ReplyKeyboardMarkup,
    Update,
)
from telegram.ext import (
    ApplicationBuilder,
    CommandHandler,
    ContextTypes,
    MessageHandler,
    CallbackQueryHandler,
    filters,
)
from telegram.error import BadRequest

# --------------------
# env / paths
# --------------------

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

TOKEN = os.environ.get("TELEGRAM_BOT_TOKEN")
if not TOKEN:
    raise RuntimeError("Set TELEGRAM_BOT_TOKEN in .env or environment.")

BASE_DIR = Path(__file__).parent.resolve()
FACEFUSION_DIR = Path(os.environ.get("FACEFUSION_DIR", BASE_DIR / "facefusion-3.5.2")).resolve()
CONFIG_PATH = Path(os.environ.get("FACEFUSION_CONFIG", FACEFUSION_DIR / "facefusion.ini")).resolve()
JOBS_PATH = Path(os.environ.get("FACEFUSION_JOBS", BASE_DIR / "jobs_path")).resolve()
SOURCE_DIR = Path(os.environ.get("FACEFUSION_SOURCES", BASE_DIR / "source_paths")).resolve()
TARGET_DIR = Path(os.environ.get("FACEFUSION_TARGETS", BASE_DIR / "target_path")).resolve()
OUTPUT_DIR = Path(os.environ.get("FACEFUSION_OUTPUTS", BASE_DIR / "output_path")).resolve()

VIDEO_EXEC_PROVIDERS = os.environ.get("FACEFUSION_VIDEO_EXEC", "cuda").split()
IMAGE_EXEC_PROVIDERS = os.environ.get("FACEFUSION_IMAGE_EXEC", "cpu").split()
VIDEO_VMS = os.environ.get("FACEFUSION_VIDEO_VMS", "strict")
IMAGE_VMS = os.environ.get("FACEFUSION_IMAGE_VMS", "strict")

AUTO_RUN = os.environ.get("FACEFUSION_AUTO_RUN", "1") != "0"
DEFAULT_MODE = "photo_video_fast"

# --------------------
# UI labels / modes
# --------------------

MODE_LABELS = {
    "photo_video_fast": "🎬 Фото → Видео (быстро)",
    "photo_video_quality": "🎥 Фото → Видео (качество)",
    "photo_photo_gpen": "🖼️ Фото → Фото (gpen)",
    "photo_photo_codeformer": "🖼️ Фото → Фото (codeformer)",
}

MODE_DESCRIPTIONS = {
    "photo_video_fast": "Быстрее, среднее качество, enhancer gfpgan.",
    "photo_video_quality": "Лучшее качество, медленнее, enhancer codeformer.",
    "photo_photo_gpen": "Фото → фото с gpen_bfr_1024, делает мягче и резче.",
    "photo_photo_codeformer": "Фото → фото с codeformer, баланс резкости/деталей.",
}

ALLOWED_IMAGE_EXT = {".jpg", ".jpeg", ".png"}
ALLOWED_VIDEO_EXT = {".mp4", ".mov"}
MAX_VIDEO_SIZE_BYTES = 60 * 1024 * 1024
MAX_VIDEO_SECONDS = 120

# --------------------
# Keyboards
# --------------------


def welcome_keyboard() -> ReplyKeyboardMarkup:
    return ReplyKeyboardMarkup([["ℹ️ Инфо", "🚀 Старт"]], resize_keyboard=True)


def mode_keyboard(show_cancel: bool) -> ReplyKeyboardMarkup:
    rows = [
        [MODE_LABELS["photo_video_fast"], MODE_LABELS["photo_video_quality"]],
        [MODE_LABELS["photo_photo_gpen"], MODE_LABELS["photo_photo_codeformer"]],
    ]
    if show_cancel:
        rows.append(["❌ Отмена"])
    return ReplyKeyboardMarkup(rows, resize_keyboard=True, one_time_keyboard=False)


def confirm_keyboard(show_cancel: bool) -> ReplyKeyboardMarkup:
    rows = [["👍 Запустить", "🔄 Заново"]]
    if show_cancel:
        rows.append(["❌ Отмена"])
    return ReplyKeyboardMarkup(rows, resize_keyboard=True, one_time_keyboard=True)


def ref_keyboard(show_cancel: bool) -> ReplyKeyboardMarkup:
    rows = [["Пропустить", "❓ Что за кадр?"]]
    if show_cancel:
        rows.append(["❌ Отмена"])
    return ReplyKeyboardMarkup(rows, resize_keyboard=True, one_time_keyboard=True)


def inline_welcome() -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(
        [
            [
                InlineKeyboardButton("ℹ️ Инфо", callback_data="info"),
                InlineKeyboardButton("🚀 Старт", callback_data="start"),
            ]
        ]
    )


def inline_mode(show_cancel: bool) -> InlineKeyboardMarkup:
    rows = [
        [
            InlineKeyboardButton(MODE_LABELS["photo_video_fast"], callback_data="mode:photo_video_fast"),
            InlineKeyboardButton(MODE_LABELS["photo_video_quality"], callback_data="mode:photo_video_quality"),
        ],
        [
            InlineKeyboardButton(MODE_LABELS["photo_photo_gpen"], callback_data="mode:photo_photo_gpen"),
            InlineKeyboardButton(MODE_LABELS["photo_photo_codeformer"], callback_data="mode:photo_photo_codeformer"),
        ],
    ]
    if show_cancel:
        rows.append([InlineKeyboardButton("❌ Отмена", callback_data="cancel")])
    return InlineKeyboardMarkup(rows)


def inline_confirm(show_cancel: bool) -> InlineKeyboardMarkup:
    rows = [
        [
            InlineKeyboardButton("👍 Запустить", callback_data="confirm_yes"),
            InlineKeyboardButton("🔄 Заново", callback_data="confirm_no"),
        ]
    ]
    if show_cancel:
        rows.append([InlineKeyboardButton("❌ Отмена", callback_data="cancel")])
    return InlineKeyboardMarkup(rows)


def inline_ref(show_cancel: bool) -> InlineKeyboardMarkup:
    rows = [[InlineKeyboardButton("Пропустить", callback_data="ref_skip")]]
    if show_cancel:
        rows.append([InlineKeyboardButton("❌ Отмена", callback_data="cancel")])
    return InlineKeyboardMarkup(rows)


def is_private_chat(chat) -> bool:
    return chat and chat.type == "private"


def choose_keyboard(chat, stage: str, show_cancel: bool):
    if is_private_chat(chat):
        if stage == "welcome":
            return welcome_keyboard()
        if stage == "mode":
            return mode_keyboard(show_cancel)
        if stage == "ref":
            return ref_keyboard(show_cancel)
        if stage == "confirm":
            return confirm_keyboard(show_cancel)
        return None
    # group inline
    if stage == "welcome":
        return inline_welcome()
    if stage == "mode":
        return inline_mode(show_cancel)
    if stage == "ref":
        return inline_ref(show_cancel)
    if stage == "confirm":
        return inline_confirm(show_cancel)
    return None


CONFIRM_YES = {"👍 запустить", "запустить", "да", "y", "yes"}
CONFIRM_NO = {"🔄 заново", "нет", "n", "no"}
REF_SKIP = {"пропустить", "skip", "0"}

MENU_INFO = {"ℹ️ инфо", "инфо", "info"}
MENU_START = {"🚀 старт", "старт", "/start"}
MENU_CANCEL = {"❌ отмена", "отмена", "/reset"}

COMMANDS = [
    BotCommand("start", "Приветствие и выбор режима"),
    BotCommand("info", "Как пользоваться ботом"),
    BotCommand("mode", "Установить режим вручную"),
    BotCommand("reset", "Отменить текущую загрузку"),
]

for folder in (JOBS_PATH, SOURCE_DIR, TARGET_DIR, OUTPUT_DIR):
    folder.mkdir(parents=True, exist_ok=True)

# --------------------
# helpers
# --------------------


def allowed_target_kind(mode: str) -> Literal["video", "image"]:
    return "video" if mode.startswith("photo_video") else "image"


def ref_needed(chat) -> bool:
    return is_private_chat(chat)


def modes_overview() -> str:
    lines = ["Доступные режимы:"]
    for key in MODE_LABELS:
        lines.append(f"• {MODE_LABELS[key]} — {MODE_DESCRIPTIONS[key]}")
    return "\n".join(lines)


def target_prompt(mode: str) -> str:
    return "Видео (mp4/mov)" if allowed_target_kind(mode) == "video" else "Фото 2 (jpg/jpeg/png)"


def confirmation_text(mode: str, target_kind: Literal["video", "image"]) -> str:
    target_label = "Видео 1 (target)" if target_kind == "video" else "Фото 2 (target)"
    return (
        f"Режим: {MODE_LABELS.get(mode, mode)}\n"
        "Источник: Фото 1\n"
        f"Цель: {target_label}\n"
        "Запускать обработку?"
    )


def detect_media_kind(message: Message) -> Optional[Tuple[File, str, Literal["image", "video"]]]:
    if message.photo:
        return message.photo[-1].get_file(), ".jpg", "image"
    if message.video:
        return message.video.get_file(), ".mp4", "video"
    if message.document:
        ext = Path(message.document.file_name or "").suffix.lower()
        if ext in ALLOWED_IMAGE_EXT:
            return message.document.get_file(), ext or ".jpg", "image"
        if ext in ALLOWED_VIDEO_EXT:
            return message.document.get_file(), ext or ".mp4", "video"
    return None


def get_video_file_size(message: Message) -> Optional[int]:
    if message.video:
        return message.video.file_size
    if message.document:
        ext = Path(message.document.file_name or "").suffix.lower()
        if ext in ALLOWED_VIDEO_EXT:
            return message.document.file_size
    return None


async def download_media(message: Message, dest_dir: Path) -> Optional[Tuple[Path, Literal["image", "video"]]]:
    detected = detect_media_kind(message)
    if not detected:
        return None
    file_future, suffix, kind = detected
    file_obj = await file_future
    dest_dir.mkdir(parents=True, exist_ok=True)
    dest_path = dest_dir / f"{uuid.uuid4().hex}{suffix}"
    await file_obj.download_to_drive(custom_path=str(dest_path))
    return dest_path, kind


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


def build_output_path(target_path: Path) -> Path:
    ext = target_path.suffix or ".mp4"
    return OUTPUT_DIR / f"{uuid.uuid4().hex}{ext}"


def is_video_path(path: Path) -> bool:
    return path.suffix.lower() in ALLOWED_VIDEO_EXT


def run_cli(args: list[str]) -> None:
    subprocess.run(args, cwd=FACEFUSION_DIR, check=True)


def resolve_models(mode: str, target: Path) -> Tuple[str, str]:
    if mode == "photo_video_fast":
        return "inswapper_128_fp16", "gfpgan_1.4"
    if mode == "photo_video_quality":
        return "hyperswap_1c_256", "codeformer"
    if mode == "photo_photo_gpen":
        return "hyperswap_1c_256", "gpen_bfr_1024"
    if mode == "photo_photo_codeformer":
        return "hyperswap_1c_256", "codeformer"
    if is_video_path(target):
        return "inswapper_128_fp16", "gfpgan_1.4"
    return "hyperswap_1c_256", "gpen_bfr_1024"


def enqueue_job(
    source: Path,
    target: Path,
    output: Path,
    mode: str,
    reference_frame_number: Optional[str],
) -> str:
    job_id = f"tg-{uuid.uuid4().hex[:8]}"
    swap_model, enh_model = resolve_models(mode, target)
    env_ref = os.environ.get("FACEFUSION_REFERENCE_FRAME") or None
    ref = reference_frame_number or env_ref

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
    if ref:
        args += ["--reference-frame-number", ref]
    run_cli(args)

    run_cli(["python", "facefusion.py", "job-submit", job_id, "--jobs-path", str(JOBS_PATH)])
    return job_id


def run_job(
    job_id: str,
    exec_providers: Optional[list[str]] = None,
    video_memory_strategy: Optional[str] = None,
) -> None:
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
    if exec_providers:
        args += ["--execution-providers", *exec_providers]
    if video_memory_strategy:
        args += ["--video-memory-strategy", video_memory_strategy]

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
            line = raw_line.rstrip("\n")
            if line:
                print(line, flush=True)

    ret = proc.wait()
    if ret != 0:
        raise subprocess.CalledProcessError(ret, args)


def queue_position(application, kind: Literal["video", "image"]) -> int:
    queue: asyncio.Queue = application.bot_data[f"job_queue_{kind}"]
    running = application.bot_data.get(f"job_running_{kind}", False)
    pos = queue.qsize()
    if running:
        pos += 1
    return pos


def has_payload(context: ContextTypes.DEFAULT_TYPE) -> bool:
    return "source_path" in context.user_data or "pending_job" in context.user_data


def is_addressed(update: Update, context: ContextTypes.DEFAULT_TYPE) -> bool:
    if update.callback_query:
        return True
    chat = update.effective_chat
    if chat and chat.type == "private":
        return True
    message = update.effective_message
    if not message:
        return False
    bot_username = context.application.bot_data.get("bot_username", "")
    if bot_username:
        text = (message.text or message.caption or "").lower()
        if f"@{bot_username.lower()}" in text:
            return True
    if message.reply_to_message and message.reply_to_message.from_user:
        if message.reply_to_message.from_user.id == context.application.bot.id:
            return True
    return False


def mark_session_active(context: ContextTypes.DEFAULT_TYPE) -> None:
    context.user_data["session_active"] = True


def session_active(context: ContextTypes.DEFAULT_TYPE) -> bool:
    return bool(context.user_data.get("session_active"))


def set_inline_anchor(context: ContextTypes.DEFAULT_TYPE, message: Message) -> None:
    context.user_data["inline_anchor"] = (message.chat_id, message.message_id)


async def try_edit_inline(context: ContextTypes.DEFAULT_TYPE, text: str, keyboard: InlineKeyboardMarkup) -> bool:
    anchor = context.user_data.get("inline_anchor")
    if not anchor:
        return False
    chat_id, message_id = anchor
    try:
        await context.bot.edit_message_text(
            chat_id=chat_id,
            message_id=message_id,
            text=text,
            reply_markup=keyboard,
        )
        return True
    except BadRequest as exc:
        if "message is not modified" in str(exc).lower():
            return True
        return False
    except Exception:
        return False


async def handle_media_group(update: Update, context: ContextTypes.DEFAULT_TYPE, chat) -> bool:
    """
    Handle media albums in group chats: allow sending source+target in one album.
    """
    message = update.effective_message
    if not message or not message.media_group_id:
        return False
    bot_data = context.bot_data.setdefault("album_cache", {})
    chat_cache = bot_data.setdefault(message.chat_id, {})
    group = chat_cache.setdefault(message.media_group_id, [])

    # basic validation before download
    video_size = get_video_file_size(message)
    if video_size and video_size > MAX_VIDEO_SIZE_BYTES:
        await send_text(update, context, "Видео должно быть до 60 МБ.", stage="mode", show_cancel=has_payload(context), prefer_edit=True)
        chat_cache.pop(message.media_group_id, None)
        return True

    if video_size:
        await send_text(update, context, "Получаю видео из альбома, подождите…", stage="mode", show_cancel=has_payload(context), prefer_edit=True)

    download = await download_media(message, TARGET_DIR)
    if not download:
        await send_text(update, context, "Нужен файл jpg/jpeg/png или mp4/mov.", stage="mode", show_cancel=has_payload(context), prefer_edit=True)
        chat_cache.pop(message.media_group_id, None)
        return True
    path, kind = download
    group.append((path, kind))

    # Wait until we have at least 2 items
    if len(group) < 2:
        return True

    # Use first two only
    source_path, source_kind = group[0]
    target_path, target_kind = group[1]
    chat_cache.pop(message.media_group_id, None)

    # must be photo + (photo/video)
    if source_kind != "image":
        await send_text(update, context, "Первым должно быть фото (source). Попробуйте снова.", stage="mode", show_cancel=False, prefer_edit=True)
        try:
            Path(source_path).unlink(missing_ok=True)
            Path(target_path).unlink(missing_ok=True)
        except Exception:
            pass
        return True

    mode = context.user_data.get("mode", DEFAULT_MODE)
    expected_kind = allowed_target_kind(mode)

    if target_kind != expected_kind:
        await send_text(
            update,
            context,
            f"В режиме {MODE_LABELS.get(mode, mode)} нужен target: {expected_kind}. Начни заново.",
            stage="mode",
            show_cancel=False,
            prefer_edit=True,
        )
        try:
            Path(source_path).unlink(missing_ok=True)
            Path(target_path).unlink(missing_ok=True)
        except Exception:
            pass
        return True

    if target_kind == "video" and is_video_too_long(Path(target_path)):
        await send_text(update, context, "Видео должно быть не длиннее 2 минут. Попробуй короче.", stage="mode", show_cancel=False, prefer_edit=True)
        try:
            Path(source_path).unlink(missing_ok=True)
            Path(target_path).unlink(missing_ok=True)
        except Exception:
            pass
        return True

    # ready to set pending_job directly
    pending_job = {
        "source": Path(source_path),
        "target": Path(target_path),
        "output": build_output_path(Path(target_path)),
        "mode": mode,
        "target_kind": target_kind,
    }
    pending_job["stage"] = "confirm"
    context.user_data["pending_job"] = pending_job
    mark_session_active(context)
    await send_text(
        update,
        context,
        confirmation_text(mode, target_kind),
        stage="confirm",
        show_cancel=True,
        prefer_edit=True,
    )
    return True


async def send_text(
    update: Update,
    context: ContextTypes.DEFAULT_TYPE,
    text: str,
    stage: str,
    show_cancel: bool,
    prefer_edit: bool = False,
) -> None:
    chat = update.effective_chat
    keyboard = choose_keyboard(chat, stage, show_cancel)
    # For private chats we always send a new message with reply keyboard
    if is_private_chat(chat):
        msg = update.effective_message
        if msg:
            await msg.reply_text(text, reply_markup=keyboard)
        return
    # group flow with inline buttons
    cq = update.callback_query
    if prefer_edit:
        if cq and cq.message:
            try:
                await cq.message.edit_text(text, reply_markup=keyboard)
                set_inline_anchor(context, cq.message)
                return
            except BadRequest as exc:
                if "message is not modified" in str(exc).lower():
                    set_inline_anchor(context, cq.message)
                    return
            except Exception:
                pass
        # try stored anchor
        if keyboard and await try_edit_inline(context, text, keyboard):
            return
    msg = update.effective_message or (cq.message if cq else None)
    if msg:
        # in groups avoid replying to the user's message; send a fresh bot message
        sent = await context.bot.send_message(chat_id=msg.chat_id, text=text, reply_markup=keyboard)
        set_inline_anchor(context, sent)

# --------------------
# workers
# --------------------


async def job_worker(application, queue: asyncio.Queue, kind: Literal["video", "image"]) -> None:
    running_flag_key = f"job_running_{kind}"
    while True:
        job_id, output_path, chat_id, target_kind = await queue.get()
        application.bot_data[running_flag_key] = True
        try:
            loop = asyncio.get_running_loop()
            exec_providers = VIDEO_EXEC_PROVIDERS if target_kind == "video" else IMAGE_EXEC_PROVIDERS
            vms = VIDEO_VMS if target_kind == "video" else IMAGE_VMS
            await loop.run_in_executor(None, run_job, job_id, exec_providers, vms)
            if output_path.suffix.lower() in ALLOWED_VIDEO_EXT:
                with output_path.open("rb") as fh:
                    await application.bot.send_video(
                        chat_id=chat_id,
                        video=fh,
                        caption=f"Готово. Job: {job_id}",
                        supports_streaming=True,
                    )
            else:
                with output_path.open("rb") as fh:
                    await application.bot.send_photo(
                        chat_id=chat_id,
                        photo=fh,
                        caption=f"Готово. Job: {job_id}",
                    )
        except FileNotFoundError:
            await application.bot.send_message(chat_id=chat_id, text="Файл результата не найден.")
        except subprocess.CalledProcessError as exc:
            logging.exception("FaceFusion run failed")
            await application.bot.send_message(chat_id=chat_id, text=f"Job {job_id} завершился с ошибкой: {exc}")
        except Exception as exc:  # noqa: BLE001
            logging.exception("Failed to send output")
            await application.bot.send_message(chat_id=chat_id, text=f"Не удалось отправить результат: {exc}")
        finally:
            queue.task_done()
            application.bot_data[running_flag_key] = False

# --------------------
# handlers
# --------------------


async def start(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    chat = update.effective_chat
    if not chat or not is_addressed(update, context):
        return
    reset_state(context)
    mark_session_active(context)
    await send_text(
        update,
        context,
        "Привет! Я помогу сделать своп.\nНажми Старт, чтобы выбрать режим, или Инфо, чтобы посмотреть инструкцию.",
        stage="welcome",
        show_cancel=False,
        prefer_edit=True,
    )


async def info_command(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    chat = update.effective_chat
    if not chat or not is_addressed(update, context):
        return
    mode = context.user_data.get("mode", DEFAULT_MODE)
    mark_session_active(context)
    await send_text(
        update,
        context,
        "Как работать:\n"
        "1) Выбери режим.\n"
        "2) Отправь фото 1 (source).\n"
        f"3) Отправь {target_prompt(mode)}.\n"
        "4) Для видео укажи кадр с нужным лицом или пропусти.\n"
        "5) Подтверди запуск.\n\n"
        "Ограничения: jpg/jpeg/png, mp4/mov, видео до 2 минут и до 60 МБ.\n\n"
        f"{modes_overview()}",
        stage="welcome",
        show_cancel=False,
        prefer_edit=True,
    )


async def help_command(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    await info_command(update, context)


async def reset_command(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    chat = update.effective_chat
    if not chat or not is_addressed(update, context):
        return
    if reset_state(context):
        await send_text(update, context, "Сбросил текущую загрузку.", stage="mode", show_cancel=False, prefer_edit=True)
    else:
        await send_text(update, context, "Нет активной загрузки.", stage="welcome", show_cancel=False, prefer_edit=True)


async def set_mode(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    chat = update.effective_chat
    if not chat or not is_addressed(update, context):
        return
    mark_session_active(context)
    if not context.args:
        await send_text(
            update,
            context,
            "Укажи режим: /mode <photo_video_fast|photo_video_quality|photo_photo_gpen|photo_photo_codeformer>\n\n"
            f"{modes_overview()}",
            stage="mode",
            show_cancel=has_payload(context),
            prefer_edit=True,
        )
        return
    mode = context.args[0].strip()
    if mode not in MODE_LABELS:
        await send_text(
            update,
            context,
            "Неизвестный режим.",
            stage="mode",
            show_cancel=has_payload(context),
            prefer_edit=True,
        )
        return
    context.user_data["mode"] = mode
    await send_text(
        update,
        context,
        f"Режим: {MODE_LABELS[mode]}\n{MODE_DESCRIPTIONS.get(mode, '')}",
        stage="mode",
        show_cancel=has_payload(context),
        prefer_edit=True,
    )


def reset_state(context: ContextTypes.DEFAULT_TYPE) -> bool:
    had_state = False
    if "pending_job" in context.user_data:
        context.user_data.pop("pending_job", None)
        had_state = True
    if "source_path" in context.user_data:
        context.user_data.pop("source_path", None)
        had_state = True
    context.user_data.pop("session_active", None)
    context.user_data.pop("inline_anchor", None)
    return had_state


async def handle_mode_button(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    message = update.effective_message
    chat = update.effective_chat
    if message is None or chat is None:
        return
    if not (is_addressed(update, context) or session_active(context)):
        return

    text_raw = (message.text or "").strip()
    text = text_raw.lower()
    payload_active = has_payload(context)

    if text in MENU_CANCEL:
        if reset_state(context):
            await send_text(update, context, "Отменено. Начните заново с выбора режима.", stage="mode", show_cancel=False, prefer_edit=True)
        else:
            await send_text(update, context, "Нет активной загрузки.", stage="welcome", show_cancel=False, prefer_edit=True)
        return
    if text in MENU_INFO:
        await info_command(update, context)
        return
    if text in MENU_START:
        await send_text(update, context, "Выбери режим:", stage="mode", show_cancel=False, prefer_edit=True)
        return

    # If awaiting ref/confirm
    if "pending_job" in context.user_data:
        pending: Dict[str, Any] = context.user_data["pending_job"]
        stage = pending.get("stage", "ref")

        if stage == "ref":
            ref_frame = None
            if text in {"❓ что за кадр?", "что за кадр?"}:
                await message.reply_text(
                    "Укажи номер кадра, на котором нужное лицо (номер = секунда * fps), "
                    "или нажми Пропустить.",
                    reply_markup=ref_keyboard(True),
                )
                return
            if text not in REF_SKIP:
                try:
                    ref_frame = str(int(text))
                except ValueError:
                    await message.reply_text("Нужен номер кадра (целое число) или Пропустить.", reply_markup=ref_keyboard(True))
                    return
            pending["ref_frame"] = ref_frame
            pending["stage"] = "confirm"
            context.user_data["pending_job"] = pending
            await message.reply_text(
                confirmation_text(pending["mode"], pending["target_kind"]),
                reply_markup=confirm_keyboard(True),
            )
            return

        if stage == "confirm":
            if text not in CONFIRM_YES and text not in CONFIRM_NO:
                await send_text(update, context, "Ответь: Запустить или Заново.", stage="confirm", show_cancel=True, prefer_edit=True)
                return
            if text in CONFIRM_NO:
                # перезапросить только цель: сохраняем source, удаляем старый target
                target_old = pending.get("target")
                if target_old:
                    try:
                        Path(target_old).unlink(missing_ok=True)  # type: ignore[arg-type]
                    except Exception:
                        pass
                context.user_data.pop("pending_job", None)
                context.user_data["source_path"] = pending["source"]
                await send_text(
                    update,
                    context,
                    "Начнём заново с цели: Фото 1 уже сохранено. Отправь новое target-фото/видео.",
                    stage="mode",
                    show_cancel=True,
                    prefer_edit=True,
                )
                return

            context.user_data.pop("pending_job", None)
            loop = asyncio.get_running_loop()
            ref_frame = pending.get("ref_frame")
            try:
                job_id = await loop.run_in_executor(
                    None,
                    enqueue_job,
                    pending["source"],
                    pending["target"],
                    pending["output"],
                    pending["mode"],
                    ref_frame,
                )
            except subprocess.CalledProcessError as exc:
                logging.exception("FaceFusion CLI failed")
                await send_text(update, context, f"Не удалось создать job: {exc}", stage="mode", show_cancel=False, prefer_edit=True)
                return

            if AUTO_RUN:
                queue_key = "job_queue_video" if pending["target_kind"] == "video" else "job_queue_image"
                queue: asyncio.Queue = context.application.bot_data[queue_key]
                queue.put_nowait((job_id, pending["output"], chat.id, pending["target_kind"]))
                pos = queue_position(context.application, pending["target_kind"])
                await send_text(
                    update,
                    context,
                    f"Job {job_id} поставлен в очередь. Место: {pos}.",
                    stage="mode",
                    show_cancel=False,
                    prefer_edit=True,
                )
            else:
                await send_text(
                    update,
                    context,
                    f"Job {job_id} создан. Запусти вручную при необходимости.",
                    stage="mode",
                    show_cancel=False,
                    prefer_edit=True,
                )
            return

    # Mode selection buttons
    for key, label in MODE_LABELS.items():
        if text_raw == label:
            context.user_data["mode"] = key
            await send_text(
                update,
                context,
                f"Режим: {label}\n{MODE_DESCRIPTIONS.get(key, '')}",
                stage="mode",
                show_cancel=payload_active,
                prefer_edit=True,
            )
            return


async def handle_media(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    message = update.effective_message
    chat = update.effective_chat
    if message is None or chat is None:
        return
    if not (is_addressed(update, context) or session_active(context)):
        return

    # Try combined upload (media group) in groups: photo+photo or photo+video in one album
    if message.media_group_id and not is_private_chat(chat):
        if await handle_media_group(update, context, chat):
            return

    user_state = context.user_data
    mode = user_state.get("mode", DEFAULT_MODE)
    expected_kind = allowed_target_kind(mode)
    video_size = get_video_file_size(message)
    is_video_message = video_size is not None

    # First file must be source photo
    if "source_path" not in user_state and "pending_job" not in user_state:
        if is_video_message:
            await send_text(update, context, "Сначала отправь Фото 1 (source).", stage="mode", show_cancel=False, prefer_edit=True)
            return
        download = await download_media(message, SOURCE_DIR)
        if not download:
            await send_text(update, context, "Нужен файл jpg/jpeg/png или mp4/mov.", stage="mode", show_cancel=False, prefer_edit=True)
            return
        source_path, kind = download
        if kind != "image":
            await send_text(update, context, "Сначала только фото (jpg/jpeg/png).", stage="mode", show_cancel=False, prefer_edit=True)
            return
        user_state["source_path"] = source_path
        await send_text(
            update,
            context,
            f"Фото 1 получено.\nРежим: {MODE_LABELS.get(mode, mode)}.\n"
            f"Теперь отправь {target_prompt(mode)}.\n"
            "Кнопка Отмена появится, когда загружен хотя бы один файл.",
            stage="mode",
            show_cancel=True,
            prefer_edit=True,
        )
        return

    if "pending_job" in user_state:
        await send_text(update, context, "Уже ждём подтверждения или кадра. Ответь на предыдущий вопрос.", stage="mode", show_cancel=True, prefer_edit=True)
        return

    if "source_path" not in user_state:
        await send_text(update, context, "Сначала отправь Фото 1.", stage="mode", show_cancel=False, prefer_edit=True)
        return

    if expected_kind == "video" and is_video_message and video_size is not None and video_size > MAX_VIDEO_SIZE_BYTES:
        await send_text(update, context, "Видео должно быть до 60 МБ. Загрузите меньший файл.", stage="mode", show_cancel=True, prefer_edit=True)
        return
    if expected_kind == "image" and is_video_message:
        reset_state(context)
        await send_text(update, context, "В этом режиме нужен второй файл — фото. Начни заново с Фото 1.", stage="mode", show_cancel=False, prefer_edit=True)
        return

    # quick ACK for large video
    if expected_kind == "video":
        await send_text(update, context, "Получаю видео, подождите…", stage="mode", show_cancel=True, prefer_edit=True)

    download = await download_media(message, TARGET_DIR)
    if not download:
        await send_text(update, context, "Нужен файл jpg/jpeg/png или mp4/mov.", stage="mode", show_cancel=True, prefer_edit=True)
        return
    target_path, target_kind = download

    if target_kind != expected_kind:
        reset_state(context)
        expected_label = "видео" if expected_kind == "video" else "фото"
        await send_text(
            update,
            context,
            f"В выбранном режиме нужен {expected_label}. Начни заново с Фото 1.",
            stage="mode",
            show_cancel=False,
            prefer_edit=True,
        )
        return

    if target_kind == "video" and is_video_too_long(target_path):
        reset_state(context)
        await send_text(update, context, "Видео должно быть не длиннее 2 минут. Попробуй короче.", stage="mode", show_cancel=False, prefer_edit=True)
        return

    source_path = user_state.pop("source_path")
    output_path = build_output_path(target_path)

    pending_job = {
        "source": source_path,
        "target": target_path,
        "output": output_path,
        "mode": mode,
        "target_kind": target_kind,
    }

    if target_kind == "video" and ref_needed(chat):
        pending_job["stage"] = "ref"
        context.user_data["pending_job"] = pending_job
        await send_text(
            update,
            context,
            "Укажи кадр с нужным лицом (номер кадра = секунда * fps) или нажми Пропустить.",
            stage="ref",
            show_cancel=True,
            prefer_edit=True,
        )
        return

    pending_job["stage"] = "confirm"
    context.user_data["pending_job"] = pending_job
    await send_text(
        update,
        context,
        confirmation_text(mode, target_kind),
        stage="confirm",
        show_cancel=True,
        prefer_edit=True,
    )


async def handle_callback(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    cq = update.callback_query
    if not cq:
        return
    await cq.answer()
    if not is_addressed(update, context):
        return
    data = cq.data or ""
    chat = update.effective_chat
    payload_active = has_payload(context)

    if data == "start":
        reset_state(context)
        mark_session_active(context)
        await send_text(update, context, "Выбери режим:", stage="mode", show_cancel=False, prefer_edit=True)
        return
    if data == "info":
        mark_session_active(context)
        await info_command(update, context)
        return
    if data == "cancel":
        if reset_state(context):
            await send_text(update, context, "Отменено. Начните заново с выбора режима.", stage="mode", show_cancel=False, prefer_edit=True)
        else:
            await send_text(update, context, "Нет активной загрузки.", stage="welcome", show_cancel=False, prefer_edit=True)
        return
    if data.startswith("mode:"):
        mode_key = data.split(":", 1)[1]
        if mode_key in MODE_LABELS:
            context.user_data["mode"] = mode_key
            await send_text(
                update,
                context,
                f"Режим: {MODE_LABELS[mode_key]}\n{MODE_DESCRIPTIONS.get(mode_key, '')}",
                stage="mode",
                show_cancel=payload_active,
                prefer_edit=True,
            )
        else:
            await send_text(update, context, "Неизвестный режим.", stage="mode", show_cancel=payload_active, prefer_edit=True)
        return

    if "pending_job" not in context.user_data:
        return
    pending: Dict[str, Any] = context.user_data["pending_job"]
    stage = pending.get("stage", "ref")

    if data == "ref_skip" and stage == "ref":
        pending["ref_frame"] = None
        pending["stage"] = "confirm"
        context.user_data["pending_job"] = pending
        await send_text(
            update,
            context,
            confirmation_text(pending["mode"], pending["target_kind"]),
            stage="confirm",
            show_cancel=True,
            prefer_edit=True,
        )
        return

    if data in {"confirm_yes", "confirm_no"} and stage == "confirm":
        if data == "confirm_no":
            target_old = pending.get("target")
            if target_old:
                try:
                    Path(target_old).unlink(missing_ok=True)  # type: ignore[arg-type]
                except Exception:
                    pass
            context.user_data.pop("pending_job", None)
            context.user_data["source_path"] = pending["source"]
            await send_text(
                update,
                context,
                "Начнём заново с цели: Фото 1 уже сохранено. Отправь новое target-фото/видео.",
                stage="mode",
                show_cancel=True,
                prefer_edit=True,
            )
            return

        # confirm_yes
        context.user_data.pop("pending_job", None)
        loop = asyncio.get_running_loop()
        ref_frame = pending.get("ref_frame")
        try:
            job_id = await loop.run_in_executor(
                None,
                enqueue_job,
                pending["source"],
                pending["target"],
                pending["output"],
                pending["mode"],
                ref_frame,
            )
        except subprocess.CalledProcessError as exc:
            logging.exception("FaceFusion CLI failed")
            await send_text(update, context, f"Не удалось создать job: {exc}", stage="mode", show_cancel=False, prefer_edit=True)
            return

        if AUTO_RUN:
            queue_key = "job_queue_video" if pending["target_kind"] == "video" else "job_queue_image"
            queue: asyncio.Queue = context.application.bot_data[queue_key]
            queue.put_nowait((job_id, pending["output"], chat.id, pending["target_kind"]))
            pos = queue_position(context.application, pending["target_kind"])
            await send_text(
                update,
                context,
                f"Job {job_id} поставлен в очередь. Место: {pos}.",
                stage="mode",
                show_cancel=False,
                prefer_edit=True,
            )
        else:
            await send_text(
                update,
                context,
                f"Job {job_id} создан. Запусти вручную при необходимости.",
                stage="mode",
                show_cancel=False,
                prefer_edit=True,
            )
        return
# --------------------
# setup
# --------------------


async def post_init(application) -> None:
    me = await application.bot.get_me()
    application.bot_data["bot_username"] = me.username or ""
    await application.bot.set_my_commands(COMMANDS)
    job_queue_video: asyncio.Queue = asyncio.Queue()
    job_queue_image: asyncio.Queue = asyncio.Queue()
    application.bot_data["job_queue_video"] = job_queue_video
    application.bot_data["job_queue_image"] = job_queue_image
    application.bot_data["job_running_video"] = False
    application.bot_data["job_running_image"] = False
    application.create_task(job_worker(application, job_queue_video, "video"))
    application.create_task(job_worker(application, job_queue_image, "image"))


def main() -> None:
    logging.basicConfig(
        format="%(asctime)s %(levelname)s %(name)s | %(message)s",
        level=logging.INFO,
    )
    logging.getLogger("httpx").setLevel(logging.WARNING)
    logging.getLogger("telegram").setLevel(logging.WARNING)

    application = ApplicationBuilder().token(TOKEN).post_init(post_init).build()
    application.add_handler(CommandHandler("start", start))
    application.add_handler(CommandHandler("help", help_command))
    application.add_handler(CommandHandler("info", info_command))
    application.add_handler(CommandHandler("reset", reset_command))
    application.add_handler(CommandHandler("mode", set_mode))
    application.add_handler(CallbackQueryHandler(handle_callback))
    application.add_handler(MessageHandler(filters.TEXT & ~filters.COMMAND, handle_mode_button))
    media_filter = filters.PHOTO | filters.VIDEO | filters.Document.ALL
    application.add_handler(MessageHandler(media_filter, handle_media))

    logging.info("Bot is starting. Private chats only.")
    application.run_polling()


if __name__ == "__main__":
    main()
