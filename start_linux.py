#!/usr/bin/env python3
"""Камеры Руслана на одном экране — автономный Linux-сервис (Hostkey Finland).

1. Запускает P2P-службу ThroughTek (OPSEP2PServer.exe под wine64).
2. По каждому регистратору из cams.json просит туннель: POST /StartP2P -> локальный порт.
3. Через туннель к web-порту логинится в API и берёт адреса RTSP (Preview/StreamUrl/Get).
   Если RTSP на отдельном порту (Ленина :554) — открывает к нему прямой RTSP-туннель.
4. Пишет go2rtc.yaml (пароли идут через переменные окружения RL_PASS_*) и www/grid.json,
   запускает go2rtc и раздачу страницы-сетки (127.0.0.1:8090).
5. Раз в HEALTH_EVERY секунд проверяет здоровье туннелей и потоков.
"""
import csv, json, logging, os, signal, socket, subprocess, sys, threading, time
from logging.handlers import RotatingFileHandler
from urllib.parse import quote, urlparse
import requests
from requests.auth import HTTPDigestAuth

HERE = os.path.dirname(os.path.abspath(__file__))
CFG = json.load(open(os.path.join(HERE, "cams.json")))
LOGS = os.path.join(HERE, "logs")
P2P_API = "http://127.0.0.1:59528"
G2R_API = "http://127.0.0.1:1984"
G2R_PORT, WEB_PORT = 1984, 8090
USER = "admin"
HEALTH_EVERY = 30
FAILS_TO_RECONNECT = 2          # 2 проверки подряд без ответа -> переоткрыть туннель
FWD_BASE = 41000                # постоянные локальные порты для go2rtc
services = {}                   # go2rtc / web / ezviz: контролируемые дочерние процессы
service_env = None
stopping = False
p2p_proc = None                 # процесс wine64 OPSEP2PServer
TUN = {}                        # (device, dev_port) -> {"seq", "port", "mode", "fwd", "dev_port"}
DEV_ID = {}                     # device -> p2p_id

os.makedirs(LOGS, exist_ok=True)
log = logging.getLogger("cams")
log.setLevel(logging.INFO)
_fh = RotatingFileHandler(os.path.join(LOGS, "cams.log"), maxBytes=5_000_000, backupCount=5, encoding="utf-8")
_fh.setFormatter(logging.Formatter("%(asctime)s %(levelname)-5s %(message)s", "%Y-%m-%d %H:%M:%S"))
_sh = logging.StreamHandler(sys.stdout)
_sh.setFormatter(logging.Formatter("%(message)s"))
log.addHandler(_fh)
log.addHandler(_sh)


def ms(t0):
    return int((time.monotonic() - t0) * 1000)


def net_iface():
    """Интерфейс по умолчанию на Linux."""
    try:
        out = subprocess.run(["ip", "route", "show", "default"], capture_output=True, text=True).stdout
        parts = out.split()
        if "dev" in parts:
            return parts[parts.index("dev") + 1]
    except Exception:
        pass
    return "eth0"


def device_password(name):
    """Пароль регистратора из cams.json или переменной окружения."""
    pwds = CFG.get("passwords") or {}
    if name in pwds:
        return str(pwds[name])
    return os.environ.get(f"RL_PASS_{name.upper()}", "")


def p2p_alive():
    try:
        r = requests.post(P2P_API + "/Check", json={}, timeout=3)
        return True
    except requests.RequestException:
        return False


def start_p2p_server():
    """Запуск ThroughTek P2P службы под wine64."""
    global p2p_proc
    if p2p_alive():
        log.info("OPSEP2PServer уже запущен — использую его")
        return True
    d = os.path.join(HERE, "p2p")
    env = dict(os.environ, WINEDEBUG="-all")
    p2p_proc = subprocess.Popen(["/usr/lib/wine/wine64", "OPSEP2PServer.exe"], cwd=d, env=env,
                                stdout=open(os.path.join(LOGS, "p2p.log"), "a"), stderr=subprocess.STDOUT)
    t0 = time.monotonic()
    for _ in range(30):
        time.sleep(0.5)
        if p2p_alive():
            log.info(f"OPSEP2PServer поднят за {ms(t0)} мс")
            return True
    log.error("OPSEP2PServer не поднялся")
    return False


class Forward:
    """Постоянный порт 127.0.0.1:listen -> текущий порт туннеля. Цель меняется без перезапуска go2rtc."""

    def __init__(self, listen_port):
        self.listen_port, self.target, self.conns, self.lock = listen_port, None, set(), threading.Lock()
        self.running = True
        self.srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.srv.bind(("127.0.0.1", listen_port))
        self.srv.listen(32)
        threading.Thread(target=self._accept, daemon=True).start()

    def close(self):
        self.running = False
        try:
            self.srv.close()
        except OSError:
            pass
        with self.lock:
            conns = list(self.conns)
        for s in conns:
            try:
                s.close()
            except OSError:
                pass

    def retarget(self, port):
        """Новая цель; старые соединения рвём, чтобы go2rtc сразу переподключился."""
        with self.lock:
            self.target = port
            old, self.conns = self.conns, set()
        for s in old:
            try:
                s.close()
            except OSError:
                pass

    def _accept(self):
        while self.running:
            try:
                c, _ = self.srv.accept()
                threading.Thread(target=self._pipe, args=(c,), daemon=True).start()
            except OSError:
                break

    def _pipe(self, c):
        target = self.target
        if not target:
            c.close()
            return
        try:
            u = socket.create_connection(("127.0.0.1", target), timeout=10)
            u.settimeout(None)
        except OSError:
            c.close()
            return
        with self.lock:
            self.conns.update((c, u))

        def copy(a, b):
            try:
                while True:
                    d = a.recv(65536)
                    if not d:
                        break
                    b.sendall(d)
            except OSError:
                pass
            for s in (a, b):
                try:
                    s.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass

        t = threading.Thread(target=copy, args=(u, c), daemon=True)
        t.start()
        copy(c, u)
        t.join()
        with self.lock:
            self.conns.discard(c)
            self.conns.discard(u)
        c.close()
        u.close()


def stop_tunnel(name, seq, timeout=10):
    if seq is None:
        return
    try:
        r = requests.post(P2P_API + "/StopP2P", json={"p2p_seq": seq, "p2p_type": "tutk"}, timeout=timeout)
        log.info(f"{name}: закрыт туннель seq={seq}: {r.text.strip()[:80]}")
    except Exception as e:
        log.warning(f"{name}: StopP2P seq={seq} — {type(e).__name__}")


def tunnel(name, p2p_id, dev_port):
    """Туннель к порту регистратора."""
    body = {"ip or id": p2p_id, "log_msg": "sdk_new", "p2p alone": False,
            "p2p type": "tutk", "protocol": "http", "web port": int(dev_port)}
    for attempt in range(1, 4):
        t0 = time.monotonic()
        try:
            r = requests.post(P2P_API + "/StartP2P", json=body, timeout=45)
            j = r.json() if r.text.strip() else {}
            if j.get("error_code") == "success":
                log.info(f"{name}: туннель к :{dev_port} -> :{j['port']} за {ms(t0)} мс, режим {j.get('p2p_mode')}")
                return j
            log.warning(f"{name}: туннель к :{dev_port} (попытка {attempt}) — {j.get('error_code') or 'пустой ответ'} за {ms(t0)} мс")
        except Exception as e:
            log.warning(f"{name}: туннель к :{dev_port} (попытка {attempt}) — {type(e).__name__} за {ms(t0)} мс")
        time.sleep(2)
    return None


_fwd_port_counter = FWD_BASE


def alloc_fwd():
    global _fwd_port_counter
    p = _fwd_port_counter
    _fwd_port_counter += 1
    return Forward(p)


def open_tunnel(name, dev_port):
    """Открыть или переоткрыть туннель и направить постоянный порт."""
    key = (name, dev_port)
    t = TUN.get(key)
    if t is None:
        t = TUN[key] = {"seq": None, "port": None, "mode": None, "fwd": alloc_fwd()}
    j = tunnel(name, DEV_ID[name], dev_port)
    if not j:
        return None
    old_seq = t["seq"]
    t.update(seq=j.get("p2p_seq"), port=j["port"], mode=j.get("p2p_mode"), dev_port=dev_port)
    t["fwd"].retarget(j["port"])
    stop_tunnel(name, old_seq)
    return t["fwd"].listen_port


def reconnect_device(name):
    log.warning(f"{name}: переподключаю туннели")
    ok = True
    for (dev, dev_port) in list(TUN):
        if dev == name:
            ok = open_tunnel(dev, dev_port) is not None and ok
    log.info(f"{name}: переподключение {'удалось' if ok else 'НЕ удалось — повторю на следующей проверке'}")
    return ok


def stream_urls(name, web_port, pwd):
    """Логин в регистратор -> {channel: {kind: (rtsp_port, path_with_query)}}."""
    base = f"http://127.0.0.1:{web_port}"
    ts = time.strftime("%Y-%m-%d@%H:%M:%S")
    s = requests.Session()
    t0 = time.monotonic()
    r = s.post(f"{base}/API/Web/Login?{ts}", auth=HTTPDigestAuth(USER, pwd), json={}, timeout=30)
    if r.status_code != 200:
        raise RuntimeError(f"логин {r.status_code} за {ms(t0)} мс")
    t_login = ms(t0)
    token = (r.json().get("data") or {}).get("token") or r.headers.get("X-csrftoken", "")
    h = {"X-csrftoken": token}
    t1 = time.monotonic()
    out = {}
    try:
        resp = s.post(f"{base}/API/Preview/StreamUrl/Get?{ts}", headers=h,
                      json={"version": "1.0", "data": {"channel": "CH1"}}, timeout=20)
        j = resp.json() if resp.status_code == 200 and resp.text.strip() else {}
        for it in (j.get("data") or {}).get("channel_info", []):
            variants = {}
            for kind, key in (("main", "mainstream_url"), ("sub", "substream_url"), ("mobile", "mobile_stream_url")):
                u = urlparse(it.get(key) or "")
                if u.scheme:
                    variants[kind] = (u.port or 554, u.path + ("?" + u.query if u.query else ""))
            if not variants:
                continue
            ch = int(str(it["channel"]).upper().replace("CH", ""))
            out[ch] = variants
    except Exception as e:
        log.warning(f"{name}: StreamUrl/Get — {e}")

    # Fallback на ChannelInfo/Get (старая прошивка)
    if not out:
        log.info(f"{name}: StreamUrl/Get недоступен, опрашиваю ChannelInfo/Get")
        try:
            r_ch = s.post(f"{base}/API/Login/ChannelInfo/Get?{ts}", headers=h,
                          json={"version": "1.0", "data": {}}, timeout=20)
            items = ((r_ch.json().get("data") or {}).get("channel_param") or {}).get("items", []) if r_ch.status_code == 200 else []
            for it in items:
                if it.get("connect_status") == "Online":
                    ch = int(str(it["channel"]).upper().replace("CH", ""))
                    ability = it.get("ability", [])
                    v = {
                        "main": (554, f"/ch{ch:02d}/0"),
                        "sub": (554, f"/ch{ch:02d}/1"),
                    }
                    if "Mobilestream" in ability:
                        v["mobile"] = (554, f"/ch{ch:02d}/2")
                    out[ch] = v
        except Exception as e:
            log.warning(f"{name}: ChannelInfo/Get — {e}")

    if not out:
        log.warning(f"{name}: ChannelInfo пуст — использую стандартные RTSP-пути для 16 каналов")
        for ch in range(1, 17):
            out[ch] = {
                "main": (554, f"/ch{ch:02d}/0"),
                "sub": (554, f"/ch{ch:02d}/1"),
                "mobile": (554, f"/ch{ch:02d}/2"),
            }

    log.info(f"{name}: логин {t_login} мс, адреса потоков {ms(t1)} мс, каналов {len(out)}")
    return out


def probe_tunnel(port, is_rtsp=False, timeout=8):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(timeout)
    try:
        s.connect(("127.0.0.1", port))
        if is_rtsp:
            s.sendall(b"OPTIONS rtsp://127.0.0.1/ RTSP/1.0\r\nCSeq: 1\r\n\r\n")
        else:
            s.sendall(b"GET / HTTP/1.0\r\nHost: 127.0.0.1\r\n\r\n")
        data = s.recv(128)
        if not data:
            return False, "EOF"
        return True, ""
    except Exception as e:
        return False, type(e).__name__
    finally:
        try:
            s.close()
        except OSError:
            pass


def restart_p2p_server():
    terminate_process(p2p_proc)
    subprocess.run(["pkill", "-9", "-x", "OPSEP2PServer.exe"], stderr=subprocess.DEVNULL)
    subprocess.run(["pkill", "-9", "-x", "wineserver64"], stderr=subprocess.DEVNULL)
    for t in TUN.values():
        t.update(seq=None, port=None, mode=None)
        t["fwd"].retarget(None)
    if start_p2p_server():
        for dev in sorted({d for d, _ in TUN}):
            reconnect_device(dev)


def ensure_tunnel(name, port):
    t = TUN.get((name, port))
    if t and t.get("port"):
        return t["fwd"].listen_port
    return open_tunnel(name, port)


def configure_device(name, pwd):
    if name not in DEV_ID:
        log.warning(f"{name}: нет P2P ID")
        return {}
    # Если регистратор старый и RTSP идёт прямо по :554 (Ленина) — открываем сразу 554
    if CFG.get("rtsp_ports", {}).get(name) == 554:
        rtsp_p = ensure_tunnel(name, 554)
        if not rtsp_p:
            log.warning(f"{name}: нет туннеля RTSP :554 — камеры пропущены")
            return {}
        result = {
            ch: {
                "main": (rtsp_p, f"/ch{ch:02d}/0"),
                "sub": (rtsp_p, f"/ch{ch:02d}/1"),
            }
            for ch in range(1, 17)
        }
        return result

    web = ensure_tunnel(name, 80) if name in DEV_ID else None
    if not web:
        log.warning(f"{name}: нет туннеля — камеры пропущены")
        return {}
    try:
        urls = stream_urls(name, web, pwd)
    except Exception as e:
        log.warning(f"{name}: {e}")
        return {}
    needed_ports = {port for variants in urls.values() for port, _ in variants.values()}
    local = {80: web} if 80 in needed_ports else {}
    for p in needed_ports:
        if p not in local:
            local[p] = ensure_tunnel(name, p)
    return {
        ch: {kind: (local.get(p), path) for kind, (p, path) in variants.items()}
        for ch, variants in urls.items()
    }


def atomic_write(path, text):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(text)
    os.replace(tmp, path)


def publish_streams(dev_streams):
    streams, grid, stream_dev = {}, [], {}
    for cam in CFG["grid"]:
        variants = dev_streams.get(cam["device"], {}).get(cam["channel"], {})
        available = {kind: variants[kind] for kind in ("sub", "mobile", "main")
                     if kind in variants and variants[kind][0]}
        if not available:
            log.warning(f"пропуск {cam['id']}")
            continue
        pref = cam.get("stream")
        default = pref if pref in available else next(iter(available))
        grid_streams = {}
        dev_var = f"RL_PASS_{cam['device'].upper()}"
        for kind, (lp, path) in available.items():
            sid = cam["id"] if kind == default else f"{cam['id']}_{kind}"
            # Источник: прямой RTSP через туннель
            streams[sid + "_src"] = f"rtsp://{USER}:${{{dev_var}}}@127.0.0.1:{lp}{path}"
            if cam["device"] == "lenina" and cam["channel"] == 4:
                # Ленина 4 уже нативно в H.264 (720p). /ch04/0 не поддерживается регистратором.
                streams[sid] = [
                    f"rtsp://127.0.0.1:8554/{cam['id']}_src",
                    f"ffmpeg:rtsp://127.0.0.1:8554/{cam['id']}_src#video=h264#raw=-preset ultrafast -tune zerolatency"
                ]
            else:
                # Мульти-источник:
                # 1. Нативный H.265 (HEVC) напрямую (0% CPU для Safari, Android TV, VLC)
                # 2. Фолбэк на ultrafast ffmpeg только если клиент не умеет HEVC
                streams[sid] = [
                    f"rtsp://127.0.0.1:8554/{sid}_src",
                    f"ffmpeg:rtsp://127.0.0.1:8554/{sid}_src#video=h264#raw=-preset ultrafast -tune zerolatency"
                ]
            grid_streams[kind] = sid
            stream_dev[sid] = cam["device"]
        grid.append({"id": cam["id"], "title": cam["title"], "default": default, "streams": grid_streams})

    for cam in CFG.get("ezviz", []):
        sid = cam["id"]
        # EZVIZ MPEG-TS transcode through ffmpeg ensures valid SDP and compliant H.264
        streams[sid] = [
            f"ffmpeg:http://127.0.0.1:8560/{sid}.ts#video=h264#raw=-preset ultrafast -tune zerolatency"
        ]
        stream_dev[sid] = "ezviz"
        grid.append({"id": sid, "title": cam["title"], "default": "default", "streams": {"default": sid}})

    # Резервируем поток для внешней публикации мозаики
    streams["mosaic_all"] = []

    lines = [
        "api:",
        f'  listen: "127.0.0.1:{G2R_PORT}"',
        '  origin: "*"',
        "rtsp:",
        '  listen: ":8554"',
        "webrtc:",
        '  listen: ":8555"',
        "  candidates:",
        "    - monobuket.tabcast.ru:8555",
        "    - 66.151.34.27:8555",
        "log:",
        "  level: info",
        "streams:" if streams else "streams: {}"
    ]
    for sid, val in streams.items():
        if val == []:
            lines.append(f"  {sid}: []")
        elif val == "" or val is None:
            lines.append(f'  {sid}: ""')
        elif isinstance(val, list):
            lines.append(f"  {sid}:")
            for item in val:
                lines.append(f"    - {item}")
        else:
            lines.append(f"  {sid}:")
            lines.append(f"    - {val}")

    preload_sids = []
    for item in grid:
        def_kind = item.get('default')
        target_sid = item.get('streams', {}).get(def_kind)
        if target_sid:
            src_sid = target_sid + '_src'
            if src_sid in streams:
                preload_sids.append(src_sid)
            elif target_sid in streams:
                preload_sids.append(target_sid)
    if preload_sids:
        lines.append('preload:')
        lines += [f'  {sid}: ""' for sid in preload_sids]
    atomic_write(os.path.join(HERE, "go2rtc.yaml"), "\n".join(lines) + "\n")
    os.makedirs(os.path.join(HERE, "www"), exist_ok=True)
    grid_json_path = os.path.join(HERE, "www", "grid.json")
    existing_tv_settings = {"buffer_ms": 2500, "clock_enabled": True}
    if os.path.exists(grid_json_path):
        try:
            with open(grid_json_path, "r", encoding="utf-8") as f:
                old_grid = json.load(f)
                if "tv_settings" in old_grid:
                    existing_tv_settings = old_grid["tv_settings"]
        except Exception:
            pass
    atomic_write(grid_json_path,
                 json.dumps({"go2rtc": G2R_PORT, "tv_settings": existing_tv_settings, "cams": grid}, ensure_ascii=False, indent=1))
    return stream_dev


def recover_missing_devices(dev_streams, passwords, stream_dev):
    missing = {c["device"] for c in CFG["grid"]
               if not any(port for port, _ in dev_streams.get(c["device"], {}).get(c["channel"], {}).values())}
    changed = False
    for name in sorted(missing):
        try:
            found = configure_device(name, passwords[name])
            if found:
                previous = dev_streams.get(name, {})
                merged = {**previous, **{ch: variants for ch, variants in found.items()
                                        if any(port for port, _ in variants.values())}}
                if merged != previous:
                    dev_streams[name] = merged
                    changed = True
        except Exception as e:
            log.warning(f"{name}: повторный опрос — {type(e).__name__}")
    if changed:
        stream_dev.clear()
        stream_dev.update(publish_streams(dev_streams))
        log.info("состав сетки обновлён после восстановления регистратора")
    return changed


def terminate_process(proc, timeout=1):
    if proc is None or proc.poll() is not None:
        return
    proc.terminate()
    try:
        proc.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait(timeout=2)


def start_go2rtc():
    terminate_process(services.get("go2rtc"))
    # Освобождаем порты go2rtc (1984 и 8554), если остался старый процесс
    subprocess.run(["fuser", "-k", "1984/tcp", "8554/tcp"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    with open(os.path.join(LOGS, "go2rtc.log"), "a") as out:
        services["go2rtc"] = subprocess.Popen(
            [os.path.join(HERE, "bin", "go2rtc"), "-config", os.path.join(HERE, "go2rtc.yaml")],
            env=service_env, cwd=HERE, stdout=out, stderr=subprocess.STDOUT)


def start_web():
    terminate_process(services.get("web"))
    services["web"] = subprocess.Popen(
        [sys.executable, "-m", "http.server", str(WEB_PORT), "--bind", "127.0.0.1",
         "--directory", os.path.join(HERE, "www")], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def start_ezviz():
    terminate_process(services.get("ezviz"))
    with open(os.path.join(LOGS, "ezviz.log"), "a") as out:
        services["ezviz"] = subprocess.Popen(
            [os.path.join(HERE, ".venv-ezviz", "bin", "python"), os.path.join(HERE, "ezviz_bridge.py")],
            cwd=HERE, stdout=out, stderr=subprocess.STDOUT)


def supervise_services():
    restarted = False
    launches = [("go2rtc", start_go2rtc), ("web", start_web)]
    if CFG.get("ezviz"):
        launches.append(("ezviz", start_ezviz))
    for name, launch in launches:
        proc = services.get(name)
        if proc is None or proc.poll() is not None:
            log.warning(f"{name}: процесс завершился, перезапускаю")
            launch()
            restarted = True
    return restarted


def video_health(state, stream_dev, prev, now):
    active, stalled, rows = set(), set(), []
    for cam, dev in sorted(stream_dev.items()):
        # EZVIZ streams don't have _src suffix, while NVR cameras do
        value = state.get(cam + "_src") or state.get(cam) or {}
        prods = value.get("producers") or []
        recv = sum(p.get("bytes_recv", p.get("recv", 0)) or 0 for p in prods)
        ids = tuple(p.get("id") for p in prods)
        viewers = len(value.get("consumers") or []) + len((state.get(cam) or {}).get("consumers") or [])
        last = prev.get(cam)
        same = last is not None and ids == last[2] and recv >= last[0]
        kbps = round((recv - last[0]) * 8 / 1000 / max(now - last[1], .001)) if same else 0
        if same and recv > last[0]:
            active.add(dev)
        elif same and viewers and last[3]:
            stalled.add(dev)
        prev[cam] = (recv, now, ids, bool(viewers))
        rows.append((cam, dev, kbps, viewers))
    return active, stalled, rows


def health_loop(stream_dev, dev_streams, passwords):
    path = os.path.join(LOGS, "health.csv")
    new = not os.path.exists(path)
    with open(path, "a", newline="") as f:
        w = csv.writer(f)
        if new:
            w.writerow(["time", "iface", "kind", "name", "device", "value", "unit", "viewers", "note"])
        prev, fails, api_fails = {}, {}, 0
        last_discovery = time.monotonic()
        while True:
            time.sleep(HEALTH_EVERY)
            now, iface = time.strftime("%Y-%m-%d %H:%M:%S"), net_iface()
            if supervise_services():
                prev.clear()
            if not p2p_alive():
                log.warning("OPSEP2PServer не отвечает — перезапускаю")
                restart_p2p_server()
                prev.clear()
                continue
            try:
                response = requests.get(G2R_API + "/api/streams", timeout=5)
                response.raise_for_status()
                st = response.json()
                if not isinstance(st, dict):
                    raise ValueError("invalid streams response")
                api_fails = 0
            except (requests.RequestException, ValueError):
                api_fails += 1
                log.warning(f"go2rtc: API не отвечает ({api_fails})")
                if api_fails >= FAILS_TO_RECONNECT:
                    start_go2rtc()
                    api_fails = 0
                prev.clear()
                continue

            active, stalled, rows = video_health(st, stream_dev, prev, time.monotonic())
            parts = []
            for cam, dev in sorted(stream_dev.items()):
                tunnels = [t for (d, _), t in TUN.items() if d == dev]
                kbps = next((r[2] for r in rows if r[0] == cam), 0)
                viewers = next((r[3] for r in rows if r[0] == cam), 0)
                w.writerow([now, iface, "stream", cam, dev, kbps, "kbit/s", viewers, ""])
                if viewers:
                    parts.append(f"{cam} {kbps} кбит/с")
            for dev in sorted({d for d, _ in TUN}):
                tunnels = [t for (d, _), t in TUN.items() if d == dev]
                if dev in active:
                    ok, note, lat = True, "active", 0
                elif dev in stalled:
                    ok, note, lat = False, "video stalled", -1
                else:
                    ok, note, lat = True, "", 0
                    for t in tunnels:
                        if not t.get("port"):
                            ok, note, lat = False, "no tunnel", -1
                            break
                        t0 = time.monotonic()
                        success, err = probe_tunnel(t["port"], is_rtsp=t.get("dev_port") == 554)
                        lat = max(lat, ms(t0))
                        if not success:
                            ok, note, lat = False, err, -1
                            break
                fails[dev] = 0 if ok else fails.get(dev, 0) + 1
                modes = "/".join(sorted({t.get("mode") or "unknown" for t in tunnels}))
                w.writerow([now, iface, "tunnel", dev, dev, lat, "ms", "", f"{note} {modes}".strip()])
                parts.append(f"{dev}/{modes}: {note or str(lat) + ' мс'}")
                if fails[dev] >= FAILS_TO_RECONNECT:
                    if reconnect_device(dev):
                        fails[dev] = 0
                        for cam, owner in stream_dev.items():
                            if owner == dev:
                                prev.pop(cam, None)
            if CFG.get("ezviz"):
                # Restart ezviz bridge if any ezviz stream is stalled for 2 checks (60s)
                ezviz_stalled = ("ezviz" in stalled) or any(
                    dev == "ezviz" and cam in prev and prev[cam][3] and (prev[cam][0] == 0 or kbps == 0)
                    for cam, dev, kbps, viewers in rows if dev == "ezviz"
                )
                fails["ezviz"] = fails.get("ezviz", 0) + 1 if ezviz_stalled else 0
                if fails["ezviz"] >= 2:
                    log.warning("EZVIZ: видеопоток завис — перезапускаю мост")
                    start_ezviz()
                    fails["ezviz"] = 0
                    for cam, owner in stream_dev.items():
                        if owner == "ezviz":
                            prev.pop(cam, None)
            if time.monotonic() - last_discovery >= 60:
                if recover_missing_devices(dev_streams, passwords, stream_dev):
                    start_go2rtc()
                    prev.clear()
                last_discovery = time.monotonic()
            f.flush()
            log.info(f"здоровье [{iface}]: " + "; ".join(parts))


def stop(*_):
    global stopping
    if stopping:
        return
    stopping = True
    log.info("остановка сервиса")
    deadline = time.monotonic() + 3
    for (dev, _), t in list(TUN.items()):
        remaining = deadline - time.monotonic()
        if remaining > 0:
            stop_tunnel(dev, t["seq"], timeout=min(.5, remaining))
        t["fwd"].close()
    for proc in list(services.values()) + ([p2p_proc] if p2p_proc else []):
        terminate_process(proc, timeout=.5)
    subprocess.run(["pkill", "-9", "-x", "OPSEP2PServer.exe"], stderr=subprocess.DEVNULL)
    subprocess.run(["pkill", "-9", "-x", "wineserver64"], stderr=subprocess.DEVNULL)
    os._exit(0)


def main():
    global service_env
    log.info(f"=== старт Linux сервиса, интерфейс: {net_iface()} ===")
    signal.signal(signal.SIGINT, stop)
    signal.signal(signal.SIGTERM, stop)
    if not start_p2p_server():
        stop()
    DEV_ID.update({v: k for k, v in CFG["devices"].items()})
    passwords = {name: device_password(name) for name in sorted({c["device"] for c in CFG["grid"]})}
    service_env = dict(os.environ)
    for name, pwd in passwords.items():
        service_env[f"RL_PASS_{name.upper()}"] = quote(pwd, safe="")
    dev_streams = {}
    for name, pwd in passwords.items():
        try:
            found = configure_device(name, pwd)
            if found:
                dev_streams[name] = found
        except Exception as e:
            log.warning(f"{name}: первичный опрос — {type(e).__name__}")
    stream_dev = publish_streams(dev_streams)
    if CFG.get("ezviz"):
        start_ezviz()
    start_go2rtc()
    start_web()
    log.info(f"Камер в сетке: {len({cam['id'] for cam in CFG['grid'] + CFG.get('ezviz', []) if cam['id'] in stream_dev})}. "
             f"Локальный доступ: http://127.0.0.1:{WEB_PORT}/grid.html")
    try:
        health_loop(stream_dev, dev_streams, passwords)
    finally:
        stop()


if __name__ == "__main__":
    main()
