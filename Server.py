#!/usr/bin/env python3
import os
import re
import time
import json
import mimetypes
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse

# config
PORT = 8000
ROOT_DIR = r"C:\Users\ali23\Documents\Desktop 2.0\QU\CMPE 482\Server Directory\dash_godfather_test"

# log file next to this script
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
LOG_PATH = os.path.join(BASE_DIR, "server_simple.log")

CHUNK_SIZE = 64 * 1024  # bytes

# mime types important for DASH
mimetypes.add_type("application/dash+xml", ".mpd")
mimetypes.add_type("video/mp4", ".m4s")
mimetypes.add_type("video/mp4", ".mp4")

RANGE_RE = re.compile(r"bytes=(\d*)-(\d*)")


def parse_range(value, total_size):
    if not value:
        return None
    m = RANGE_RE.fullmatch(value.strip())
    if not m:
        return None
    a, b = m.groups()
    if a == "" and b == "":
        return None

    if a == "":
        length = int(b)
        if length <= 0:
            return None
        start = max(total_size - length, 0)
        end = total_size - 1
    else:
        start = int(a)
        end = total_size - 1 if b == "" else int(b)
        if start > end:
            return None

    start = max(0, min(start, total_size))
    end = max(0, min(end, total_size - 1))
    return start, end





def log_line(**kv):
    line = " ".join(f"{k}={json.dumps(v, ensure_ascii=False)}" for k, v in kv.items())
    print(line, flush=True)
    with open(LOG_PATH, "a", encoding="utf8") as f:
        f.write(line + "\n")


class Handler(BaseHTTPRequestHandler):
    server_version = "DASH/1"

    def log_message(self, fmt, *args):
        pass

    def do_HEAD(self):
        self._serve(head_only=True)

    def do_GET(self):
        self._serve(head_only=False)

    def _serve(self, head_only):
        t0 = time.time()
        err = None
        path = urlparse(self.path).path
        full = os.path.join(ROOT_DIR, path.lstrip("/"))
        status = 0
        bytes_sent = 0
        complete = False
        ctype = mimetypes.guess_type(full)[0] or "application/octet-stream"


        try:
            if not os.path.isfile(full):
                self.send_error(404)
                status = 404
                return

            total_size = os.path.getsize(full)
            r = parse_range(self.headers.get("Range"), total_size)

            if r:
                start, end = r
                length = end - start + 1
                self.send_response(206)
                self.send_header("Content-Type", ctype)
                self.send_header("Accept-Ranges", "bytes")
                self.send_header("Content-Range", f"bytes {start}-{end}/{total_size}")
                self.send_header("Content-Length", str(length))
                self.end_headers()
                if not head_only:
                    with open(full, "rb") as f:
                        f.seek(start)
                        remaining = length
                        while remaining > 0:
                            chunk = f.read(min(CHUNK_SIZE, remaining))
                            if not chunk:
                                break
                            self.wfile.write(chunk)
                            sent = len(chunk)
                            bytes_sent += sent
                            remaining -= sent
                    complete = (bytes_sent == length)
                status = 206
            else:
                self.send_response(200)
                self.send_header("Content-Type", ctype)
                self.send_header("Content-Length", str(total_size))
                self.end_headers()
                if not head_only:
                    with open(full, "rb") as f:
                        while True:
                            chunk = f.read(CHUNK_SIZE)
                            if not chunk:
                                break
                            self.wfile.write(chunk)
                            bytes_sent += len(chunk)
                    complete = (bytes_sent == total_size)
                status = 200

        except ConnectionResetError:
            err = "client_closed"
            status = status or 499
        except Exception as e:
            err = str(e)
            try:
                self.send_error(500)
            except Exception:
                pass
            status = 500
        finally:
            dur_ms = int((time.time() - t0) * 1000.0)
            size_for_log = os.path.getsize(full) if os.path.isfile(full) else 0
            log_line(
                time=time.strftime("%Y-%m-%d %H:%M:%S", time.localtime()),
                method=self.command,
                path=path,
                status=status,
                file_size=size_for_log,
                bytes_sent=bytes_sent,
                dur_ms=dur_ms,
                complete=bool(complete),
                error=err,
            )


def run():
    os.chdir(ROOT_DIR)
    httpd = ThreadingHTTPServer(("", PORT), Handler)
    print(f"Serving {ROOT_DIR} on port {PORT}. Log file at {LOG_PATH}")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()


if __name__ == "__main__":
    run()
