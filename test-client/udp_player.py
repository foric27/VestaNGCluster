#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Визуальный UDP-плеер для VestaNGClusterFlowStudio.

Открывает окно OpenCV с трансляцией H.264 Annex B (UDP порт 5004)
и накладывает overlay: разрешение, FPS, битрейт, статус синхронизации,
а также ошибки потока (заморозка, низкий FPS, пропуск кадров).

При старте ждёт появления потока и не закрывается при обрыве.
Минимальное кэширование для мгновенного отображения.

Требует:  python -m pip install -r requirements.txt

Пример запуска:
    python udp_player.py
    python udp_player.py --host 192.168.40.2
"""

import argparse
import ctypes
import io
import os
import sys

# Эти параметры должны быть установлены ДО импорта cv2: OpenCV читает их
# при инициализации FFmpeg-бэкенда. Они отключают накопительное ожидание
# и уменьшают время анализа UDP/H.264 потока.
os.environ.setdefault(
    "OPENCV_FFMPEG_CAPTURE_OPTIONS",
    "fflags;nobuffer|flags;low_delay|probesize;16777216|analyzeduration;10000000|max_delay;0|timeout;500000|rw_timeout;500000|err_detect;ignore_err|h264dec;skip_frame;default|loglevel;error",
)

if sys.platform == "win32":
    # На Windows консоль часто использует CP866/CP1251;
    # принудительно переключаем кодовую страницу консоли и Python-stdio на UTF-8.
    kernel32 = ctypes.windll.kernel32
    kernel32.SetConsoleOutputCP(65001)
    kernel32.SetConsoleCP(65001)
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8")

import cv2
import json
import numpy as np
import socket
import threading
import time
from datetime import datetime
from PIL import Image, ImageDraw, ImageFont

# ---------------------------------------------------------------------------
# Константы сети (соответствуют ProductConfig.kt)
# ---------------------------------------------------------------------------
DEFAULT_VIDEO_PORT = 5004
DEFAULT_STATUS_PORT = 5001
DEFAULT_BIND_HOST = "0.0.0.0"
LOW_LATENCY_FIFO_SIZE = 1316
LOW_LATENCY_BUFFER_SIZE = 65536
FRAME_DRAIN_LIMIT = 1
MAX_DECODE_STALL_MS = 1500
VIDEO_PROBE_TIMEOUT_MS = 100

WINDOW_TITLE = "VestaNGClusterFlowStudio Stream"


def get_now() -> str:
    """Возвращает метку времени для логов."""
    return datetime.now().strftime("%H:%M:%S.%f")[:-3]


def _format_sync_time(raw: str) -> str:
    """Преобразует время из формата приложения (например, 143022+0300) в читаемый вид.

    Поддерживаемые входные форматы:
      - 143022+0300   →  14:30:22 +03:00
      - 143022-0500   →  14:30:22 -05:00
      - 143022        →  14:30:22
      - любое другое  →  возвращается без изменений
    """
    if not raw or raw == "—":
        return raw

    raw = raw.strip()

    # Формат HHMMSS±HHMM (14 символов) или HHMMSS±HH (11 символов)
    import re
    m = re.fullmatch(r"(\d{6})([+-]\d{2,4})", raw)
    if m:
        time_part = m.group(1)
        offset_raw = m.group(2)
        hh = time_part[0:2]
        mm = time_part[2:4]
        ss = time_part[4:6]
        # Форматируем offset
        sign = offset_raw[0]
        offset_digits = offset_raw[1:]
        if len(offset_digits) == 4:
            off_hh = offset_digits[0:2]
            off_mm = offset_digits[2:4]
        elif len(offset_digits) == 2:
            off_hh = offset_digits
            off_mm = "00"
        else:
            off_hh = offset_digits
            off_mm = "00"
        return f"{hh}:{mm}:{ss} {sign}{off_hh}:{off_mm}"

    # Формат HHMMSS (6 цифр)
    if re.fullmatch(r"\d{6}", raw):
        return f"{raw[0:2]}:{raw[2:4]}:{raw[4:6]}"

    # Неизвестный формат — оставляем как есть
    return raw


# ---------------------------------------------------------------------------
# Отрисовка текста через Pillow (поддержка кириллицы)
# ---------------------------------------------------------------------------
_pil_font = None


def _get_font(size: int = 20):
    """Ленивая загрузка шрифта Pillow."""
    global _pil_font
    if _pil_font is None:
        try:
            _pil_font = ImageFont.truetype("arial.ttf", size)
        except OSError:
            _pil_font = ImageFont.load_default()
    return _pil_font


def draw_text_pil(
    frame: np.ndarray,
    text: str,
    pos: tuple,
    color: tuple = (0, 255, 0),
    size: int = 20,
) -> np.ndarray:
    """Рисует одну строку на кадре OpenCV через Pillow."""
    img = Image.fromarray(cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))
    draw = ImageDraw.Draw(img)
    font = _get_font(size)
    draw.text(pos, text, font=font, fill=color)
    return cv2.cvtColor(np.array(img), cv2.COLOR_RGB2BGR)


def draw_overlay_pil(frame, overlay_items):
    """Рисует overlay на переданном фрагменте кадра за одну конвертацию.

    overlay_items: список кортежей (текст, цвет_RGB)
    """
    img = Image.fromarray(cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))
    draw = ImageDraw.Draw(img)
    font = _get_font(18)

    for i, (line, color) in enumerate(overlay_items):
        y = 28 + i * 26
        draw.text((15, y), line, font=font, fill=color)

    return cv2.cvtColor(np.array(img), cv2.COLOR_RGB2BGR)


# ---------------------------------------------------------------------------
# Класс-плеер
# ---------------------------------------------------------------------------
class Player:
    def __init__(self, host: str, video_port: int, status_port: int, headless: bool = False):
        self.host = host
        self.video_port = video_port
        self.status_port = status_port
        self.headless = headless
        self.stop_event = threading.Event()
        self.lock = threading.Lock()

        # Статус синхронизации (UDP 5001)
        self.status = {"vid": "—", "time": "—", "lang": "—"}
        self.status_received_at = 0.0
        self.status_fresh_until = 0.0

        # Видео-метрики
        self.fps = 0.0
        self.resolution = "—"

        # Метрики ошибок потока
        self.frames_ok = 0
        self.frames_lost_total = 0
        self.consecutive_lost = 0
        self.last_frame_time = time.time()
        self.freeze_ms = 0.0

    # -----------------------------------------------------------------------
    # Фоновый поток: приём статуса
    # -----------------------------------------------------------------------
    def _status_loop(self):
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            sock.bind((self.host, self.status_port))
        except OSError as exc:
            print(
                f"[{get_now()}] [СТАТУС ОШИБКА] "
                f"Не удалось открыть порт {self.status_port}: {exc}"
            )
            return

        sock.settimeout(0.5)
        print(f"[{get_now()}] [СТАТУС] Слушаю UDP {self.host}:{self.status_port}")

        while not self.stop_event.is_set():
            try:
                data, _ = sock.recvfrom(4096)
            except socket.timeout:
                continue
            except OSError:
                break

            try:
                obj = json.loads(data.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError):
                continue

            with self.lock:
                # Обновляем поле только если оно присутствует и непустое;
                # иначе сохраняем предыдущее значение до следующего валидного пакета.
                if obj.get("vid"):
                    self.status["vid"] = obj["vid"]
                if obj.get("time"):
                    self.status["time"] = obj["time"]
                if obj.get("lang"):
                    self.status["lang"] = obj["lang"]
                self.status_received_at = time.time()
                self.status_fresh_until = time.time() + 1.0
            print(
                f"[{get_now()}] [СТАТУС] "
                f"vid={self.status['vid']} "
                f"time={_format_sync_time(self.status['time'])} "
                f"lang={self.status['lang']}"
            )

        sock.close()
        print(f"[{get_now()}] [СТАТУС] Поток остановлен")

    def _has_video_packet(self) -> bool:
        """Быстро проверяет наличие UDP-видео, не открывая FFmpeg."""
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.settimeout(VIDEO_PROBE_TIMEOUT_MS / 1000.0)
        try:
            sock.bind((self.host, self.video_port))
            sock.recvfrom(2048)
            return True
        except socket.timeout:
            return False
        except OSError as exc:
            print(
                f"[{get_now()}] [ВИДЕО ОШИБКА] "
                f"Не удалось проверить порт {self.video_port}: {exc}"
            )
            return False
        finally:
            sock.close()

    def _open_capture(self, url: str):
        """Открывает VideoCapture совместимым способом для opencv-python."""
        cap = cv2.VideoCapture(url, cv2.CAP_FFMPEG)
        cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)
        return cap

    # -----------------------------------------------------------------------
    # Главный поток: видео + отрисовка overlay
    # -----------------------------------------------------------------------
    def run(self):
        # Запускаем фоновый приём статуса
        t_status = threading.Thread(target=self._status_loop, daemon=True)
        t_status.start()

        if self.headless:
            print(f"[{get_now()}] [РЕЖИМ] Headless — только логи, без окна", flush=True)
        else:
            print(f"[{get_now()}] [РЕЖИМ] Визуальный — окно OpenCV", flush=True)

        # URL для FFmpeg-бэкенда OpenCV.
        # Большой fifo_size создаёт заметную задержку, поэтому держим
        # UDP-очередь маленькой и при переполнении сбрасываем старые пакеты.
        url = (
            f"udp://@{self.host}:{self.video_port}"
            f"?overrun_nonfatal=1"
            f"&fifo_size={LOW_LATENCY_FIFO_SIZE}"
            f"&buffer_size={LOW_LATENCY_BUFFER_SIZE}"
            f"&max_delay=0"
        )
        print(f"[{get_now()}] [ВИДЕО] Ожидаю поток {url} ...", flush=True)
        if self.headless:
            print("Режим только логов. Нажмите Ctrl+C для выхода.", flush=True)
        else:
            print("Нажмите 'q' или Esc в окне для выхода.", flush=True)

        if not self.headless:
            cv2.namedWindow(WINDOW_TITLE, cv2.WINDOW_NORMAL)
            cv2.resizeWindow(WINDOW_TITLE, 1920, 640)

        cap = None
        frame = None
        frame_count = 0
        last_fps_time = time.time()
        last_decode_attempt = time.time()
        last_stats_time = time.time()
        waiting = True
        warmup_frames = 30  # Пропускаем первые кадры для стабилизации декодера
        headless_decode_errors = 0
        last_error_log = time.time()

        while not self.stop_event.is_set():
            now = time.time()

            # Если захват неактивен — пытаемся открыть
            if cap is None:
                if not self._has_video_packet():
                    if not self.headless:
                        frame = self._make_waiting_frame("ОЖИДАНИЕ ПОТОКА...")
                        if waiting:
                            print(f"[{get_now()}] [ВИДЕО] Ожидание первого UDP-пакета...")
                            waiting = False
                        cv2.imshow(WINDOW_TITLE, frame)
                        if (cv2.waitKey(30) & 0xFF) in (ord("q"), 27):
                            self.stop_event.set()
                            break
                    else:
                        if waiting:
                            print(f"[{get_now()}] [ВИДЕО] Ожидание первого UDP-пакета на порту {self.video_port}...")
                            waiting = False
                        # В headless-режиме ждём дольше между проверками
                        time.sleep(0.5)
                    continue

                cap = self._open_capture(url)
                if not cap.isOpened():
                    cap.release()
                    cap = None
                    if not self.headless:
                        frame = self._make_waiting_frame("ОЖИДАНИЕ ПОТОКА...")
                        if waiting:
                            print(f"[{get_now()}] [ВИДЕО] Ожидание потока...")
                            waiting = False
                        cv2.imshow(WINDOW_TITLE, frame)
                        if (cv2.waitKey(100) & 0xFF) in (ord("q"), 27):
                            self.stop_event.set()
                            break
                    else:
                        if waiting:
                            print(f"[{get_now()}] [ВИДЕО] VideoCapture не открылся, жду поток...")
                            waiting = False
                        time.sleep(0.5)
                    continue
                else:
                    print(f"[{get_now()}] [ВИДЕО] Поток подключен.")
                    waiting = True

            # Читаем кадр. Если OpenCV уже накопил несколько кадров,
            # сбрасываем старые через grab/retrieve и показываем самый свежий.
            # Защита от зависания: если чтение занимает слишком много времени,
            # принудительно пересоздаём захват.
            read_start = time.time()
            ok, next_frame = cap.read()
            read_elapsed_ms = (time.time() - read_start) * 1000.0
            decode_elapsed_ms = (time.time() - last_decode_attempt) * 1000.0
            last_decode_attempt = time.time()

            # Если чтение слишком долгое — декодер завис, пересоздаём
            if read_elapsed_ms > MAX_DECODE_STALL_MS:
                print(f"[{get_now()}] [ВИДЕО] Зависание чтения ({read_elapsed_ms:.0f} мс), пересоздаю захват...")
                cap.release()
                cap = None
                warmup_frames = 30  # Снова пропускаем первые кадры после восстановления
                if not self.headless:
                    frame = self._make_waiting_frame("ПЕРЕЗАГРУЗКА ДЕКОДЕРА...")
                    cv2.imshow(WINDOW_TITLE, frame)
                    if (cv2.waitKey(100) & 0xFF) in (ord("q"), 27):
                        self.stop_event.set()
                        break
                continue
            if ok:
                drained = 0
                while drained < FRAME_DRAIN_LIMIT:
                    grabbed = cap.grab()
                    if not grabbed:
                        break
                    retrieved, fresh_frame = cap.retrieve()
                    if not retrieved:
                        break
                    next_frame = fresh_frame
                    drained += 1

                # Пропускаем первые кадры для стабилизации декодера H.264
                if warmup_frames > 0:
                    warmup_frames -= 1
                    if warmup_frames == 0:
                        h, w = next_frame.shape[:2]
                        print(f"[{get_now()}] [ВИДЕО] Декодер стабилизирован. Размер кадра: {w}x{h}")
                    frame = None
                    continue

                # Проверяем, что кадр имеет корректные размеры
                if next_frame is not None and next_frame.shape[0] > 10 and next_frame.shape[1] > 10:
                    frame = next_frame
                else:
                    # Кадр слишком маленький или повреждён — пропускаем
                    continue

                with self.lock:
                    self.frames_ok += 1
                    self.consecutive_lost = 0
                    self.last_frame_time = time.time()
                    self.freeze_ms = decode_elapsed_ms if decode_elapsed_ms > MAX_DECODE_STALL_MS else 0.0
                waiting = True
            else:
                # Кадр не получен — возможно, ошибка декодирования или потеря пакета
                with self.lock:
                    self.consecutive_lost += 1
                    self.frames_lost_total += 1
                    self.freeze_ms = (time.time() - self.last_frame_time) * 1000.0

                # В headless-режиме считаем ошибки декодирования
                if self.headless:
                    headless_decode_errors += 1
                    # Логируем ошибки декодирования не чаще раза в 5 секунд
                    if headless_decode_errors % 50 == 0:
                        now_err = time.time()
                        if now_err - last_error_log >= 5.0:
                            print(f"[{get_now()}] [ВИДЕО] Ошибки декодирования: {headless_decode_errors} (пропускаю)")
                            last_error_log = now_err
                    # Пересоздаём захват после 200 ошибок декодирования
                    if headless_decode_errors >= 200:
                        print(f"[{get_now()}] [ВИДЕО] Слишком много ошибок декодирования, пересоздаю захват...")
                        cap.release()
                        cap = None
                        warmup_frames = 30
                        headless_decode_errors = 0
                        waiting = False
                        continue

                # Не закрываемся — продолжаем пытаться читать
                if self.freeze_ms > 2000:
                    # Слишком долго нет данных — пересоздаём захват
                    cap.release()
                    cap = None
                    warmup_frames = 30  # Снова пропускаем первые кадры после восстановления
                    if not self.headless:
                        frame = self._make_waiting_frame("ПОТЕРЯ ПОТОКА - ОЖИДАНИЕ...")
                        if waiting:
                            print(f"[{get_now()}] [ВИДЕО] Потеря потока, ожидание...")
                            waiting = False
                        cv2.imshow(WINDOW_TITLE, frame)
                        if (cv2.waitKey(100) & 0xFF) in (ord("q"), 27):
                            self.stop_event.set()
                            break
                    else:
                        if waiting:
                            print(f"[{get_now()}] [ВИДЕО] Потеря потока, пересоздаю захват...")
                            waiting = False
                    continue

            if frame is not None:
                frame_count += 1
                elapsed = now - last_fps_time
                if elapsed >= 1.0:
                    with self.lock:
                        self.fps = frame_count / elapsed
                    frame_count = 0
                    last_fps_time = now

                h, w = frame.shape[:2]
                with self.lock:
                    self.resolution = f"{w}x{h}"

                with self.lock:
                    status = self.status.copy()
                    fps = self.fps
                    res = self.resolution
                    freeze_ms = self.freeze_ms
                    consecutive = self.consecutive_lost
                    status_received_at = self.status_received_at
                    status_fresh_until = self.status_fresh_until

                # Оцениваем битрейт на основе размера декодированного кадра
                estimated_bitrate = 0.0
                if fps > 0:
                    estimated_bitrate = frame.nbytes * fps * 8 / 100 / 1_000_000.0

                # В headless-режиме выводим статистику в консоль раз в секунду
                if self.headless:
                    stats_elapsed = now - last_stats_time
                    if stats_elapsed >= 1.0:
                        status_age = now - status_received_at
                        print(
                            f"[{get_now()}] СТАТ: {res} | "
                            f"FPS={fps:.1f} | "
                            f"Битрейт={estimated_bitrate:.2f} Мбит/с | "
                            f"Режим={status['vid']} | "
                            f"Время={_format_sync_time(status['time'])} | "
                            f"Язык={status['lang']} | "
                            f"Возраст_статуса={status_age:.1f}с"
                        )
                        last_stats_time = now
                        headless_decode_errors = 0
                else:
                    # Формируем список ошибок потока
                    errors = []
                    if freeze_ms > 1000:
                        errors.append("ЗАВИСАНИЕ ДЕКОДЕРА" if consecutive == 0 else "ПОТЕРЯ ПОТОКА")
                    if 0 < fps < 10:
                        errors.append("НИЗКИЙ FPS")
                    if consecutive > 0:
                        errors.append(f"ПРОПУЩЕНО КАДРОВ: {consecutive}")

                    # Цветовая индикация свежести статуса (синхронизация и язык)
                    status_age = now - status_received_at
                    if now < status_fresh_until:
                        sync_color = (255, 255, 255)  # белый — мигание при обновлении
                        lang_color = (255, 255, 255)
                    elif status_age < 1.0:
                        sync_color = (0, 255, 0)      # зелёный — свежий
                        lang_color = (0, 255, 0)
                    elif status_age < 3.0:
                        sync_color = (0, 255, 255)    # жёлтый — устаревает
                        lang_color = (0, 255, 255)
                    else:
                        sync_color = (255, 0, 0)      # красный — давно не обновлялся
                        lang_color = (255, 0, 0)

                    # Формируем overlay
                    overlay_items = [
                        (f"Разрешение   : {res}", (0, 255, 0)),
                        (f"Кадр/с       : {fps:.1f}", (0, 255, 0)),
                        (
                            f"Битрейт      : {estimated_bitrate:.2f} Мбит/с"
                            if estimated_bitrate > 0
                            else "Битрейт      : —",
                            (0, 255, 0),
                        ),
                        (f"Режим        : {status['vid']}", (0, 255, 0)),
                        (f"Синхронизация: {_format_sync_time(status['time'])}", sync_color),
                        (f"Язык         : {status['lang']}", lang_color),
                    ]

                    if errors:
                        overlay_items.append(("", (0, 255, 0)))
                        for err in errors:
                            overlay_items.append((err, (255, 0, 0)))

                    # Рисуем полупрозрачную чёрную подложку
                    # Ограничиваем размеры overlay размерами кадра, чтобы не выйти за границы
                    overlay_h = min(len(overlay_items) * 26 + 20, h)
                    max_w = min(640, w)
                    sub_img = frame[0:overlay_h, 0:max_w]
                    rect = sub_img.copy()
                    cv2.rectangle(rect, (0, 0), (max_w, overlay_h), (0, 0, 0), -1)
                    cv2.addWeighted(rect, 0.55, sub_img, 0.45, 0, sub_img)
                    frame[0:overlay_h, 0:max_w] = sub_img

                    # Выводим текст только на маленьком ROI overlay: это не гоняет
                    # полный видеокадр через Pillow и снижает риск зависаний.
                    frame[0:overlay_h, 0:max_w] = draw_overlay_pil(sub_img, overlay_items)

                    cv2.imshow(WINDOW_TITLE, frame)

            # Обработка нажатий клавиш (и обновление окна)
            if not self.headless:
                key = cv2.waitKey(1) & 0xFF
                if key == ord("q") or key == 27:          # q или Esc
                    self.stop_event.set()
                    break
            else:
                time.sleep(0.01)  # Небольшая задержка в headless-режиме

        if cap is not None:
            cap.release()
        if not self.headless:
            cv2.destroyAllWindows()
        self.stop_event.set()
        t_status.join(timeout=1.0)
        print(f"[{get_now()}] [ГЛАВНЫЙ] Завершено.")

    # -----------------------------------------------------------------------
    # Вспомогательный метод: кадр-заглушка при ожидании
    # -----------------------------------------------------------------------
    def _make_waiting_frame(self, message: str) -> np.ndarray:
        """Создаёт серый кадр с текстом ожидания (широкий, как нативное разрешение)."""
        img = np.zeros((640, 1280, 3), dtype=np.uint8)
        img[:] = (40, 40, 40)
        img = draw_text_pil(img, message, (440, 280), color=(0, 255, 255), size=36)
        return img


# ---------------------------------------------------------------------------
# Точка входа
# ---------------------------------------------------------------------------
def main():
    parser = argparse.ArgumentParser(
        description="Визуальный UDP-плеер для VestaNGClusterFlowStudio"
    )
    parser.add_argument(
        "--host",
        default=DEFAULT_BIND_HOST,
        help="IP-интерфейс для прослушивания (по умолчанию 0.0.0.0)",
    )
    parser.add_argument(
        "--video-port",
        type=int,
        default=DEFAULT_VIDEO_PORT,
        help=f"UDP-порт видео (по умолчанию {DEFAULT_VIDEO_PORT})",
    )
    parser.add_argument(
        "--status-port",
        type=int,
        default=DEFAULT_STATUS_PORT,
        help=f"UDP-порт статуса (по умолчанию {DEFAULT_STATUS_PORT})",
    )
    parser.add_argument(
        "--headless",
        action="store_true",
        help="Режим только логов: без открытия окна, вывод статистики в консоль",
    )
    args = parser.parse_args()

    player = Player(args.host, args.video_port, args.status_port, headless=args.headless)
    try:
        player.run()
    except KeyboardInterrupt:
        print(f"\n[{get_now()}] [ГЛАВНЫЙ] Прервано пользователем.")
        player.stop_event.set()


if __name__ == "__main__":
    main()
