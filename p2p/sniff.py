# Логгер вместо RSP2PServer на 59528: записывает запросы клиента, отвечает ошибкой.
import http.server, json, sys, time
LOG = open(sys.argv[1], "a")
class H(http.server.BaseHTTPRequestHandler):
    def _h(self):
        n = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(n).decode(errors="replace") if n else ""
        LOG.write(json.dumps({"t": time.strftime("%H:%M:%S"), "m": self.command, "p": self.path,
                              "h": dict(self.headers), "b": body}, ensure_ascii=False) + "\n"); LOG.flush()
        out = b'{"error_code":"success"}' if self.path == "/Check" else b'{"error_code":"Time out"}'
        self.send_response(200); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(out))); self.end_headers(); self.wfile.write(out)
    do_GET = do_POST = _h
    def log_message(self, *a): pass
http.server.ThreadingHTTPServer(("127.0.0.1", 59528), H).serve_forever()
