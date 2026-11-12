#!/usr/bin/env python3
"""Настройки потоков регистратора Redline через P2P-туннель.

  python3 stream_cfg.py read <device> [Kind]               — прочитать и сохранить копию (backup/)
  python3 stream_cfg.py set  <device> <CHn> key=val ...     — поменять ТОЛЬКО канал CHn, затем перечитать и сверить
  python3 stream_cfg.py restore <device> <backup.json>      — вернуть сохранённую копию

Kind по умолчанию MobileStream (SubStream, MainStream — тоже можно).
Нужна запущенная P2P-служба (start.py её поднимает) или запускается своя.
"""
import json, os, sys, time, subprocess, copy
import requests
from requests.auth import HTTPDigestAuth

HERE = os.path.dirname(os.path.abspath(__file__))
CFG = json.load(open(os.path.join(HERE, "cams.json")))
P2P_API = "http://127.0.0.1:59528"
BACKUP = os.path.join(HERE, "backup")
os.makedirs(BACKUP, exist_ok=True)


def pwd(device):
    pwds = CFG.get("passwords") or {}
    if device in pwds:
        return str(pwds[device])
    env_p = os.environ.get(f"RL_PASS_{device.upper()}")
    if env_p:
        return env_p
    try:
        return subprocess.run(["security", "find-generic-password", "-s", "ruslan-cams-redline", "-a", "admin", "-w"],
                              capture_output=True, text=True).stdout.rstrip("\n")
    except Exception:
        return ""


def ensure_p2p():
    try:
        requests.post(P2P_API + "/Check", json={}, timeout=2)
        return None
    except requests.RequestException:
        d = os.path.join(HERE, "p2p")
        p = subprocess.Popen([os.path.join(d, "RSP2PServer")], cwd=d, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        time.sleep(2.5)
        return p


def session(device):
    pid = {v: k for k, v in CFG["devices"].items()}[device]
    j = requests.post(P2P_API + "/StartP2P", timeout=60, json={
        "ip or id": pid, "log_msg": "sdk_new", "p2p alone": False, "p2p type": "tutk", "protocol": "http", "web port": 80
    }).json()
    if j.get("error_code") != "success":
        sys.exit(f"туннель: {j}")
    seq = j.get("p2p_seq")
    base = f"http://127.0.0.1:{j['port']}"
    s = requests.Session()
    r = s.post(f"{base}/API/Web/Login?{time.strftime('%Y-%m-%d@%H:%M:%S')}", auth=HTTPDigestAuth("admin", pwd(device)), json={}, timeout=30)
    if r.status_code != 200:
        if seq is not None:
            requests.post(P2P_API + "/StopP2P", json={"p2p_seq": seq, "p2p_type": "tutk"}, timeout=5)
        sys.exit(f"логин {r.status_code}")
    s.headers["X-csrftoken"] = (r.json().get("data") or {}).get("token") or r.headers.get("X-csrftoken", "")
    return s, base, seq


def call(s, base, path, data):
    r = s.post(f"{base}/API/{path}?{time.strftime('%Y-%m-%d@%H:%M:%S')}", json={"version": "1.0", "data": data}, timeout=30)
    try:
        return r.status_code, r.json()
    except Exception:
        return r.status_code, r.text[:300]


def conv(v):
    for t in (int, float):
        try:
            return t(v)
        except ValueError:
            pass
    return {"true": True, "false": False}.get(v.lower(), v)


def main():
    mode, device = sys.argv[1], sys.argv[2]
    kind = "SubStream"
    child = ensure_p2p()
    s, base, seq = session(device)
    try:
        if mode == "read":
            kind = sys.argv[3] if len(sys.argv) > 3 else kind
            code, j = call(s, base, f"StreamConfig/{kind}/Get", {})
            fn = os.path.join(BACKUP, f"{device}_{kind}_{time.strftime('%Y%m%d-%H%M%S')}.json")
            json.dump(j, open(fn, "w"), ensure_ascii=False, indent=1)
            print(f"Get {code} → копия {fn}")
            print(json.dumps(j.get("data", j), ensure_ascii=False, indent=1)[:4000])
        elif mode == "set":
            idx = 3
            if sys.argv[idx] in ("SubStream", "MobileStream", "MainStream"):
                kind = sys.argv[idx]
                idx += 1
            ch = sys.argv[idx]
            changes = dict(a.split("=", 1) for a in sys.argv[idx + 1:])
            code, cur = call(s, base, f"StreamConfig/{kind}/Get", {})
            fn = os.path.join(BACKUP, f"{device}_{kind}_before-set_{time.strftime('%Y%m%d-%H%M%S')}.json")
            json.dump(cur, open(fn, "w"), ensure_ascii=False, indent=1)
            data = copy.deepcopy(cur["data"])
            # Remove unconfigured channels so Redline doesn't throw 400 param_error
            data["channel_info"] = {c: ch_data for c, ch_data in data["channel_info"].items() if "reason" not in ch_data}
            chn = data["channel_info"][ch]
            for k, v in changes.items():
                if k not in chn:
                    sys.exit(f"поля {k} нет у {ch}; есть: {list(chn)}")
                print(f"{ch}.{k}: {chn[k]!r} → {conv(v)!r}")
                chn[k] = conv(v)
            code, res = call(s, base, f"StreamConfig/{kind}/Set", data)
            print(f"Set {code}: {json.dumps(res, ensure_ascii=False)[:300]}")
            time.sleep(2)
            code, after = call(s, base, f"StreamConfig/{kind}/Get", {})
            got = after["data"]["channel_info"][ch]
            ok = all(got.get(k) == conv(v) for k, v in changes.items())
            others_same = all(after["data"]["channel_info"][c] == cur["data"]["channel_info"][c]
                              for c in cur["data"]["channel_info"] if c != ch)
            print(f"сверка {ch}: {'OK' if ok else 'НЕ СОВПАЛО'}; " + ", ".join(f"{k}={got.get(k)!r}" for k in changes))
            print(f"остальные каналы не тронуты: {'да' if others_same else 'НЕТ — проверить!'}")
            print(f"откат: python3 stream_cfg.py restore {device} {fn} {kind}")
        elif mode == "restore":
            bk = json.load(open(sys.argv[3]))
            kind = sys.argv[4] if len(sys.argv) > 4 else "SubStream"
            data = copy.deepcopy(bk["data"])
            data["channel_info"] = {c: ch_data for c, ch_data in data["channel_info"].items() if "reason" not in ch_data}
            code, res = call(s, base, f"StreamConfig/{kind}/Set", data)
            print(f"restore {code}: {json.dumps(res, ensure_ascii=False)[:300]}")
    finally:
        try:
            call(s, base, "Web/Logout", {})
        except Exception:
            pass
        if seq is not None:
            try:
                requests.post(P2P_API + "/StopP2P", json={"p2p_seq": seq, "p2p_type": "tutk"}, timeout=5)
            except Exception:
                pass
        if child:
            child.terminate()


if __name__ == "__main__":
    main()
