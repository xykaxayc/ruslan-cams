#!/usr/bin/env python3
"""Fault-Tolerant Mosaic Generator for Ruslan TV.

- Combines 8 cameras into a single smooth 1080p RTSP stream at 12 fps.
- Each camera input is checked: if a stream is offline or drops packets, it automatically
  falls back to a placeholder image so that the rest of the mosaic keeps playing seamlessly.
- Pushes the combined stream to go2rtc under the stream name `mosaic_all`.
"""
import json
import logging
import os
import signal
import subprocess
import sys
import time
import urllib.request

LOG_FILE = "/opt/ruslan-cams/logs/mosaic.log"
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)-5s %(message)s",
    handlers=[
        logging.FileHandler(LOG_FILE, encoding="utf-8"),
        logging.StreamHandler(sys.stdout)
    ]
)
log = logging.getLogger("mosaic")

MOSAIC_DIR = "/opt/ruslan-cams/mosaics"
GO2RTC_API = "http://127.0.0.1:1984"

# 8 cameras ordered in 4 columns x 2 rows
# Row 0: Karamova 5, Karamova 7, Kirtbaya 2, Kirtbaya 3
# Row 1: Lenina 4, Lenina 6, EZVIZ fridge, EZVIZ general
CAMERAS = [
    {"id": "karamova_5", "name": "Карамова 5"},
    {"id": "karamova_7", "name": "Карамова 7"},
    {"id": "kirtbaya_2", "name": "Киртбая 2"},
    {"id": "kirtbaya_3", "name": "Киртбая 3"},
    {"id": "lenina_4", "name": "Ленина 4"},
    {"id": "lenina_6", "name": "Ленина 6"},
    {"id": "ezviz_fridge_entry", "name": "EZVIZ Хол-ник"},
    {"id": "ezviz_general", "name": "EZVIZ Общий"}
]

def get_stream_key(data, cam_id):
    if (cam_id + "_src") in data:
        return cam_id + "_src"
    if cam_id in data:
        return cam_id
    return None

def get_active_go2rtc_streams():
    try:
        req = urllib.request.Request(f"{GO2RTC_API}/api/streams")
        with urllib.request.urlopen(req, timeout=3) as resp:
            data = json.load(resp)
            active = {}
            for cam in CAMERAS:
                cid = cam["id"]
                key = get_stream_key(data, cid)
                if key and key in data:
                    info = data.get(key) or {}
                    producers = info.get("producers") or []
                    has_bytes = any((p.get("bytes_recv", 0) or 0) > 0 for p in producers)
                    has_receivers = any(len(p.get("receivers") or []) > 0 for p in producers)
                    if producers and has_bytes and has_receivers:
                        active[cid] = key
            return active
    except Exception as e:
        log.warning(f"Failed to query go2rtc: {e}")
        return {}

def build_ffmpeg_cmd(active_streams):
    inputs = []
    filter_inputs = []

    for i, cam in enumerate(CAMERAS):
        cam_id = cam["id"]
        placeholder = os.path.join(MOSAIC_DIR, f"{cam_id}_offline.png")

        if cam_id in active_streams:
            rtsp_key = active_streams[cam_id]
            inputs.extend([
                "-rtsp_transport", "tcp",
                "-timeout", "5000000",
                "-thread_queue_size", "1024",
                "-probesize", "10000000",
                "-analyzeduration", "10000000",
                "-fflags", "+genpts+discardcorrupt",
                "-err_detect", "ignore_err",
                "-i", f"rtsp://127.0.0.1:8554/{rtsp_key}"
            ])
            # Scale each tile to 480x540 (half height of 1080p for 2 rows)
            filter_inputs.append(
                f"[{i}:v]scale=480:540:force_original_aspect_ratio=decrease,"
                f"pad=480:540:(ow-iw)/2:(oh-ih)/2:color=0x18181b,fps=15,settb=AVTB,setpts=N/(15*TB)[v{i}]"
            )
        else:
            # Fallback to offline placeholder
            inputs.extend([
                "-loop", "1",
                "-framerate", "15",
                "-i", placeholder
            ])
            filter_inputs.append(
                f"[{i}:v]scale=480:540:force_original_aspect_ratio=decrease,"
                f"pad=480:540:(ow-iw)/2:(oh-ih)/2:color=0x18181b,fps=15,settb=AVTB,setpts=N/(15*TB)[v{i}]"
            )

    # Grid layout: 4 columns x 2 rows = 1920x1080
    layout = "0_0|w0_0|w0+w1_0|w0+w1+w2_0|0_h0|w4_h0|w4+w5_h0|w4+w5+w6_h0"
    v_tags = "".join([f"[v{i}]" for i in range(8)])
    filter_graph = ";".join(filter_inputs) + f";{v_tags}xstack=inputs=8:layout={layout}[outv]"

    cmd = [
        "ffmpeg", "-hide_banner", "-loglevel", "warning",
        *inputs,
        "-filter_complex", filter_graph,
        "-map", "[outv]",
        "-c:v", "libx264",
        "-threads", "2",
        "-preset", "ultrafast",
        "-tune", "zerolatency",
        "-b:v", "2000k",
        "-maxrate", "2600k",
        "-bufsize", "1500k",
        "-pix_fmt", "yuv420p",
        "-g", "30",
        "-f", "rtsp",
        "-rtsp_transport", "tcp",
        "rtsp://127.0.0.1:8554/mosaic_all"
    ]
    return cmd

def register_mosaic_stream():
    """Ensure mosaic_all endpoint is present in go2rtc without overriding it with empty src."""
    try:
        req = urllib.request.Request(f"{GO2RTC_API}/api/streams")
        with urllib.request.urlopen(req, timeout=3) as resp:
            data = json.load(resp)
            if "mosaic_all" in data:
                return  # already configured properly in go2rtc.yaml
        # If somehow missing, create empty stream container
        req = urllib.request.Request(f"{GO2RTC_API}/api/streams?name=mosaic_all", method="PUT")
        with urllib.request.urlopen(req, timeout=3) as resp:
            log.info("Registered mosaic_all stream container in go2rtc")
    except Exception as e:
        log.warning(f"Failed to check/register mosaic_all stream in go2rtc: {e}")

def main():
    log.info("Starting Fault-Tolerant Mosaic Generator")
    register_mosaic_stream()

    proc = None
    stable_active = None
    candidate_active = None
    candidate_since = 0

    def cleanup(*_):
        nonlocal proc
        if proc:
            proc.terminate()
            try:
                proc.wait(timeout=2)
            except Exception:
                proc.kill()
        sys.exit(0)

    signal.signal(signal.SIGINT, cleanup)
    signal.signal(signal.SIGTERM, cleanup)

    while True:
        try:
            active = get_active_go2rtc_streams()
            now = time.time()

            should_restart = False
            if proc is None or proc.poll() is not None:
                should_restart = True
                stable_active = active
                candidate_active = None
            elif active != stable_active:
                if candidate_active != active:
                    candidate_active = active
                    candidate_since = now
                elif now - candidate_since >= 30:
                    log.info("Active cameras change confirmed stable for 30s, applying...")
                    stable_active = active
                    candidate_active = None
                    should_restart = True

            if should_restart:
                if proc and proc.poll() is None:
                    log.info("Restarting ffmpeg mosaic...")
                    proc.terminate()
                    try:
                        proc.wait(timeout=2)
                    except Exception:
                        proc.kill()

                cmd = build_ffmpeg_cmd(stable_active or active)
                log.info(f"Launching ffmpeg mosaic (Active cams: {list((stable_active or active).keys())})...")
                proc = subprocess.Popen(cmd)

            time.sleep(5)
        except Exception as e:
            log.error(f"Error in mosaic loop: {e}")
            time.sleep(5)

if __name__ == "__main__":
    main()
