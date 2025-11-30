# server.py
from flask import Flask, send_from_directory, request, jsonify
import os
import csv
from datetime import datetime

BASE_DIR = os.path.dirname(os.path.abspath(__file__))

# NEW: directory for all log files
LOG_DIR = os.path.join(BASE_DIR, "logs")
os.makedirs(LOG_DIR, exist_ok=True)

app = Flask(__name__, static_folder=BASE_DIR, static_url_path='')

# -------------------------------------------------------------------
# Quality lookup tables (match your earlier VMAF/PSNR/SSIM + bitrates)
# -------------------------------------------------------------------

GODFATHER_QUALITIES = {
    "v144": {
        "name": "144p", "bitrateKbps": 140,
        "psnr": 24.584986, "ssim": 0.937088, "vmaf": 19.265094
    },
    "v240": {
        "name": "240p", "bitrateKbps": 363,
        "psnr": 24.844758, "ssim": 0.947607, "vmaf": 33.249116
    },
    "v360": {
        "name": "360p", "bitrateKbps": 743,
        "psnr": 24.869610, "ssim": 0.955107, "vmaf": 43.997127
    },
    "v480": {
        "name": "480p", "bitrateKbps": 1418,
        "psnr": 24.904091, "ssim": 0.959170, "vmaf": 49.117189
    },
    "v720": {
        "name": "720p", "bitrateKbps": 3836,
        "psnr": 25.000623, "ssim": 0.964280, "vmaf": 52.853629
    },
    "v1080": {
        "name": "1080p", "bitrateKbps": 6724,
        "psnr": 24.943455, "ssim": 0.965533, "vmaf": 54.056773
    },
    "v1440": {
        "name": "1440p", "bitrateKbps": 9608,
        "psnr": 24.948999, "ssim": 0.966464, "vmaf": 54.329214
    },
    "v2160": {
        "name": "2160p", "bitrateKbps": 19200,
        "psnr": 24.984136, "ssim": 0.968931, "vmaf": 54.655291
    },
    "v2160orig": {
        "name": "2160p_original", "bitrateKbps": 59200,
        "psnr": 40.0, "ssim": 1.0, "vmaf": 100.0
    }
}

AVATAR_QUALITIES = {
    "v144": {
        "name": "144p", "bitrateKbps": 146,
        "psnr": 21.925848, "ssim": 0.903569, "vmaf": 3.113049
    },
    "v240": {
        "name": "240p", "bitrateKbps": 375,
        "psnr": 22.085529, "ssim": 0.912304, "vmaf": 4.172016
    },
    "v360": {
        "name": "360p", "bitrateKbps": 758,
        "psnr": 22.105539, "ssim": 0.921861, "vmaf": 10.979776
    },
    "v480": {
        "name": "480p", "bitrateKbps": 1426,
        "psnr": 22.148476, "ssim": 0.929292, "vmaf": 11.050656
    },
    "v720": {
        "name": "720p", "bitrateKbps": 3814,
        "psnr": 22.255094, "ssim": 0.942557, "vmaf": 28.709828
    },
    "v1080": {
        "name": "1080p", "bitrateKbps": 6680,
        "psnr": 22.208164, "ssim": 0.947110, "vmaf": 34.428899
    },
    "v1440": {
        "name": "1440p", "bitrateKbps": 9545,
        "psnr": 22.216935, "ssim": 0.949680, "vmaf": 41.277826
    },
    "v2160": {
        "name": "2160p", "bitrateKbps": 19100,
        "psnr": 22.254343, "ssim": 0.968931, "vmaf": 54.655291
    },
    "v2160orig": {
        "name": "2160p_original", "bitrateKbps": 60000,
        "psnr": 25.0, "ssim": 1.0, "vmaf": 100.0
    }
}

# ---------------------------------------------------------------
# CSV helpers (now write into LOG_DIR)
# ---------------------------------------------------------------

def init_csv(filename, header):
    path = os.path.join(LOG_DIR, filename)
    if not os.path.exists(path):
        with open(path, "w", newline="", encoding="utf-8") as f:
            writer = csv.writer(f)
            writer.writerow(header)

# Session-level files
init_csv("startupDelay.csv",
         ["timestamp", "client_ip", "manifest", "algorithm", "scenario",
          "sessionId", "startupDelay_s", "sessionDuration_s"])

init_csv("rebuffer.csv",
         ["timestamp", "client_ip", "manifest", "algorithm", "scenario",
          "sessionId", "totalRebufferTime_s", "rebufferRatio", "rebufferCount"])

# Segment / running-average files
init_csv("bitrate.csv",
         ["timestamp", "client_ip", "manifest", "segmentCount", "avgBitrateKbps"])

init_csv("qualityFidelity.csv",
         ["timestamp", "client_ip", "manifest", "segmentPath", "qualityName",
          "segmentCount", "avgBitrateKbps", "avgVMAF", "avgPSNR", "avgSSIM"])

# ---------------------------------------------------------------
# Running average storage: keyed by (client_ip, manifest_id)
# ---------------------------------------------------------------
SEGMENT_STATS = {}  # (ip, manifestId) -> dict


def get_quality_info(manifest_id, subpath):
    parts = subpath.split("/")
    if len(parts) < 3:
        return None
    if parts[0] != "segments":
        return None

    quality_dir = parts[1]  # e.g. v360

    if manifest_id == "godfather":
        return GODFATHER_QUALITIES.get(quality_dir)
    else:
        return AVATAR_QUALITIES.get(quality_dir)


def log_segment_request(manifest_id, subpath):
    q = get_quality_info(manifest_id, subpath)
    if q is None:
        return

    client_ip = request.remote_addr or "unknown"
    key = (client_ip, manifest_id)

    stats = SEGMENT_STATS.setdefault(
        key,
        {"count": 0, "sum_bitrate": 0.0, "sum_vmaf": 0.0,
         "sum_psnr": 0.0, "sum_ssim": 0.0}
    )

    stats["count"] += 1
    stats["sum_bitrate"] += q["bitrateKbps"]
    stats["sum_vmaf"] += q["vmaf"]
    stats["sum_psnr"] += q["psnr"]
    stats["sum_ssim"] += q["ssim"]

    count = stats["count"]
    avg_bitrate = stats["sum_bitrate"] / count
    avg_vmaf = stats["sum_vmaf"] / count
    avg_psnr = stats["sum_psnr"] / count
    avg_ssim = stats["sum_ssim"] / count

    timestamp = datetime.utcnow().isoformat()

    # qualityFidelity.csv (in LOG_DIR)
    with open(os.path.join(LOG_DIR, "qualityFidelity.csv"), "a", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow([
            timestamp, client_ip, manifest_id, subpath, q["name"],
            count, avg_bitrate, avg_vmaf, avg_psnr, avg_ssim
        ])

    # bitrate.csv (in LOG_DIR)
    with open(os.path.join(LOG_DIR, "bitrate.csv"), "a", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow([
            timestamp, client_ip, manifest_id, count, avg_bitrate
        ])

# ---------------------------------------------------------------
# Routes
# ---------------------------------------------------------------

@app.route("/")
def index():
    return send_from_directory(BASE_DIR, "index.html")


@app.route("/dash_godfather_test/<path:subpath>")
def serve_godfather(subpath):
    log_segment_request("godfather", subpath)
    return send_from_directory(os.path.join(BASE_DIR, "dash_godfather_test"), subpath)


@app.route("/dash_avatar_test/<path:subpath>")
def serve_avatar(subpath):
    log_segment_request("avatar", subpath)
    return send_from_directory(os.path.join(BASE_DIR, "dash_avatar_test"), subpath)


@app.route("/logMetrics", methods=["POST"])
def log_metrics():
    data = request.get_json(force=True) or {}
    timestamp = datetime.utcnow().isoformat()
    client_ip = request.remote_addr or "unknown"

    session_id = data.get("sessionId", "")
    algorithm = data.get("algorithm", "")
    scenario = data.get("scenario", "")
    manifest = data.get("manifest", "")

    startupDelay = float(data.get("startupDelay", 0.0))
    sessionDurationSec = float(data.get("sessionDurationSec", 0.0))
    totalRebufferTime = float(data.get("totalRebufferTime", 0.0))
    rebufferRatio = float(data.get("rebufferRatio", 0.0))
    rebufferCount = int(data.get("rebufferCount", 0))

    # startupDelay.csv
    with open(os.path.join(LOG_DIR, "startupDelay.csv"), "a", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow([
            timestamp, client_ip, manifest, algorithm, scenario,
            session_id, startupDelay, sessionDurationSec
        ])

    # rebuffer.csv
    with open(os.path.join(LOG_DIR, "rebuffer.csv"), "a", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow([
            timestamp, client_ip, manifest, algorithm, scenario,
            session_id, totalRebufferTime, rebufferRatio, rebufferCount
        ])

    return jsonify({"status": "ok"})


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8000, debug=True)
