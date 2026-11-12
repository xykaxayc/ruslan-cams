#!/usr/bin/env python3
import http.server
import json
import os
import sys

HERE = "/opt/ruslan-cams"
WWW = os.path.join(HERE, "www")
CAMS_FILE = os.path.join(HERE, "cams.json")
GRID_FILE = os.path.join(WWW, "grid.json")

def atomic_write(path, data):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(data)
    os.replace(tmp, path)

class ApiHandler(http.server.BaseHTTPRequestHandler):
    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def do_POST(self):
        if self.path == "/api/save-grid":
            try:
                length = int(self.headers.get("Content-Length", 0))
                body = self.rfile.read(length)
                payload = json.loads(body.decode("utf-8"))
                new_cams = payload.get("cams")

                # 1. Update www/grid.json
                with open(GRID_FILE, "r", encoding="utf-8") as gf:
                    grid_data = json.load(gf)

                if new_cams is not None:
                    if not isinstance(new_cams, list):
                        raise ValueError("cams must be a list")
                    grid_data["cams"] = new_cams

                if "tv_settings" in payload and isinstance(payload["tv_settings"], dict):
                    grid_data["tv_settings"] = payload["tv_settings"]

                atomic_write(GRID_FILE, json.dumps(grid_data, ensure_ascii=False, indent=1))

                # 2. Synchronize cams.json so server restarts keep the exact order
                if new_cams is not None:
                    with open(CAMS_FILE, "r", encoding="utf-8") as cf:
                        cams_data = json.load(cf)

                    existing_map = {c.get("id"): c for c in cams_data.get("grid", [])}
                    new_grid_list = []
                    for item in new_cams:
                        cid = item.get("id")
                        if cid in existing_map:
                            rec = dict(existing_map[cid])
                            rec["title"] = item.get("title", rec.get("title", cid))
                            new_grid_list.append(rec)
                        elif "_" in cid and not cid.startswith("ezviz"):
                            parts = cid.split("_")
                            dev = parts[0]
                            ch = parts[1]
                            if ch.isdigit():
                                new_grid_list.append({
                                    "id": cid,
                                    "device": dev,
                                    "channel": int(ch),
                                    "title": item.get("title", cid),
                                    "stream": item.get("default", "sub")
                                })

                    if new_grid_list:
                        cams_data["grid"] = new_grid_list
                        atomic_write(CAMS_FILE, json.dumps(cams_data, ensure_ascii=False, indent=2))

                resp = json.dumps({"status": "ok", "count": len(new_cams) if new_cams is not None else 0}).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.send_header("Content-Length", str(len(resp)))
                self.end_headers()
                self.wfile.write(resp)
            except Exception as e:
                err = json.dumps({"status": "error", "message": str(e)}).encode("utf-8")
                self.send_response(500)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.send_header("Content-Length", str(len(err)))
                self.end_headers()
                self.wfile.write(err)
            return

        self.send_error(404, "Not Found")

class ThreadedHTTPServer(http.server.ThreadingHTTPServer):
    allow_reuse_address = True

if __name__ == "__main__":
    server = ThreadedHTTPServer(("127.0.0.1", 8091), ApiHandler)
    server.serve_forever()
