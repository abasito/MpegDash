# Simple DASH Streaming Server & Android Player

This repo contains:

- A **minimal Python HTTP server** that correctly serves **MPEG-DASH content** (MPD manifests + `.m4s` segments) with byte-range support.
- An **Android player APK** that can stream a DASH video when you give it the URL to an `.mpd` file hosted by the server.

## Requirements

- **Python** 3.8+
- A folder containing:
  - your DASH **manifest** (`.mpd`)
  - the corresponding **segments** (`.m4s`, `.mp4`, etc.)
- An **Android device/emulator** to install the APK

---

## Project Files (Server)

Main server script:

`server_simple.py      # the Python DASH HTTP server server_simple.log     # auto-created log file (same folder as script)`

> The server code uses `ThreadingHTTPServer` and `BaseHTTPRequestHandler` and serves files from a configurable `ROOT_DIR`.

---

## How the Server Works (Quick Overview)

- Serves files from `ROOT_DIR` (configured in the script).
- Uses `mimetypes` to set correct Content-Type (e.g. `.mpd`, `.m4s`, `.mp4`).
- Implements `Range` header parsing so DASH players can request partial content.
- Logs each request to `server_simple.log` with:
  - HTTP status
  - requested path
  - file size
  - bytes actually sent
  - time taken (ms)
  - whether the response was complete

---

## Configuration

Open the Python script and adjust these lines near the top:

`PORT = 8000 ROOT_DIR = r"C:\Users\Abood\Videos\DASH\ladderfrfrfr\ladderfrfrfr\dash"`

### `ROOT_DIR`

- Set this to the **folder that contains your `.mpd` and segment files**.
- Example on Windows:
  `ROOT_DIR = r"D:\media\my_dash_content"`
- Example on macOS/Linux:
  `ROOT_DIR = "/home/username/videos/dash"`

### `PORT`

- Default is `8000`.
- Change it if you want the server to listen on a different port:
  `PORT = 9000`
- If you change the port, remember to also change it in the URL you put into the Android app.

## 🎬 Sample DASH Content (Godfather 5-Minute Clip) ← INSERT HERE

To quickly test the server and the app, you can download a ready-made DASH folder containing:

- `godfather.mpd`
- All `.m4s` video segments
- Correct folder structure

👉 Download the sample clip (ZIP):  
https://qucloud-my.sharepoint.com/:u:/g/personal/aa2204061_qu_edu_qa/EbRt8aMdN49Ou5PRr7n9BPcB7C53omoE2KPyKmVTiNnJ-g?e=nb6S57

**How to use it:**

1. Download and unzip the file
2. Move the folder into your server’s `ROOT_DIR`
3. Run the Python server
4. Enter the URL in the app:

## Android App (APK) – How to Use It

1. **Download the APK**

   - Go to this GitHub repository’s **Releases** section.
   - Download the provided **APK file** (e.g. `dash-player.apk`).

2. **Install the APK on your device**

   - Transfer the APK to your Android phone/emulator.
   - Enable installing from unknown sources if needed.
   - Install the APK.

3. **Find your server’s IP address**

   - If phone and PC are on the **same Wi-Fi**:
     - On your PC, run `ipconfig` (Windows) or `ifconfig`/`ip a` (Linux/macOS) and find your LAN IP, e.g. `192.168.1.10`.
   - If you’re using an **Android emulator**:
     - Often you can use `10.0.2.2` as the host address.

4. **Build the MPD URL**

   - General format:
     `http://<server-ip>:<port>/<relative-path-to>.mpd`
   - Examples:
     `http://192.168.1.10:8000/movie/godfather.mpd http://10.0.2.2:8000/test.mpd`

### **1. Adaptive (Default)**

- Uses **ExoPlayer’s built-in Adaptive Bitrate (ABR)** system.
- ExoPlayer automatically chooses the best quality by monitoring:
  - Estimated network bandwidth
  - Buffer level
  - Video track bitrates
- No custom logic — this is the “normal” Android ABR that most streaming apps use.

---

### **2. Fixed**

- You choose a specific resolution/bitrate manually.
- The player **locks** to that quality and stays there.
- If a segment fails to download (e.g., a **404** on a high-quality track), the app will automatically **drop to the next lower bitrate** to keep playback running.
- No adaptive switching unless failure forces a downgrade.

---

### **3. RL (Reinforcement Learning)**

- Uses our custom **RL-based ABR controller** (not ExoPlayer’s default).
- Every second, the app:
  - Measures bandwidth and buffer
  - Builds a feature vector
  - Runs the **ONNX model** (your trained RL policy)
  - Chooses the optimal bitrate based on the model’s output
