# RLServer.py
from flask import Flask, send_from_directory, request, jsonify
import csv
import os

app = Flask("RLServer", static_url_path="", static_folder=".")

# --- logging directory + file ---
LOG_DIR = "logs_rl"
os.makedirs(LOG_DIR, exist_ok=True)

QUALITY_FIDELITY_CSV = os.path.join(LOG_DIR, "qualityFidelity_RL.csv")

# Create CSV with header if it doesn't exist yet
if not os.path.isfile(QUALITY_FIDELITY_CSV):
    with open(QUALITY_FIDELITY_CSV, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow([
            "segmentCount",
            "avgBitrateKbps",
            "avgVMAF",
            "avgPSNR",
            "avgSSIM"
        ])

# running sums (reset when you restart RLServer)
aggregate = {
    "segmentCount": 0,
    "sumBitrateKbps": 0.0,
    "sumVMAF": 0.0,
    "sumPSNR": 0.0,
    "sumSSIM": 0.0
}


@app.route("/")
def root():
    # serve the RL HTML as the homepage
    return send_from_directory(".", "IndexRL.html")


@app.route("/<path:filename>")
def static_files(filename):
    # serve mpd, segments, model, etc.
    return send_from_directory(".", filename)


@app.post("/logMetrics")
def log_metrics():
    """
    Expect JSON like:
    {
      "segmentIndex": 12,
      "bitrateKbps": 1500,
      "vmaf": 49.1,
      "psnr": 24.9,
      "ssim": 0.96,
      "algorithm": "rl_onnx",
      "scenario": "baseline",
      "manifest": "dash_godfather_test/godfather_test.mpd"
    }
    Only bitrate/vmaf/psnr/ssim are actually needed for the running averages.
    """
    data = request.get_json(force=True) or {}

    bitrate_kbps = float(data.get("bitrateKbps", 0.0))
    vmaf = float(data.get("vmaf", 0.0))
    psnr = float(data.get("psnr", 0.0))
    ssim = float(data.get("ssim", 0.0))

    # update global sums
    aggregate["segmentCount"] += 1
    n = aggregate["segmentCount"]

    aggregate["sumBitrateKbps"] += bitrate_kbps
    aggregate["sumVMAF"] += vmaf
    aggregate["sumPSNR"] += psnr
    aggregate["sumSSIM"] += ssim

    avg_bitrate = aggregate["sumBitrateKbps"] / n
    avg_vmaf = aggregate["sumVMAF"] / n
    avg_psnr = aggregate["sumPSNR"] / n
    avg_ssim = aggregate["sumSSIM"] / n

    # append a row with the updated running averages
    with open(QUALITY_FIDELITY_CSV, "a", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow([n, avg_bitrate, avg_vmaf, avg_psnr, avg_ssim])

    return jsonify({"status": "ok", "segmentCount": n})


if __name__ == "__main__":
    # Run:  source ~/cmpe482-venv/bin/activate
    #       python3 RLServer.py
    app.run(host="0.0.0.0", port=8000, debug=True)
