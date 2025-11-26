@echo off
setlocal

REM ================== INPUT FILE ==================
set "IN=C:\Users\Abood\Downloads\The.Godfather.1972.2160p.BluRay.REMUX.HEVC.DTS-HD.MA.TrueHD.5.1-FGT\The.Godfather.1972.2160p.BluRay.REMUX.HEVC.DTS-HD.MA.TrueHD.5.1-FGT.mkv"

REM ================== WORK FOLDER =================
set "WORK=%USERPROFILE%\Videos\godfather_hdr10_clip"
if not exist "%WORK%" mkdir "%WORK%"
pushd "%WORK%"

REM 1) Trim 5-minute segment, snap to keyframe (I-frame) near 02:35:00
REM    -ss before -i = keyframe seek (start frame is I-frame)
REM    Only keep video 0 and audio 2, no subs or metadata
ffmpeg ^
 -ss 01:35:00 ^
 -i "%IN%" ^
 -t 00:06:40 ^
 -map 0:v:0 -map 0:a:2 ^
 -c:v copy -c:a copy ^
 -map_metadata -1 -map_chapters -1 -dn -sn ^
 -avoid_negative_ts make_zero ^
 "step1_cut.mkv"
if errorlevel 1 goto :fail

REM 2) Extract video track as elementary stream + timestamps
mkvextract tracks "step1_cut.mkv" 0:"video.dv.hevc"
if errorlevel 1 goto :fail

mkvextract timestamps_v2 "step1_cut.mkv" 0:"video_timecodes.txt"
if errorlevel 1 goto :fail

REM 3) Remove Dolby Vision RPU -> pure HDR10 (no re-encode)
dovi_tool remove -i "video.dv.hevc" -o "video.hdr10.hevc"
if errorlevel 1 goto :fail

REM 4) Remux cleaned HDR10 video + original audio (no subs/metadata)
mkvmerge -o "step2_hdr10.mkv" ^
 --timecodes 0:"video_timecodes.txt" ^
 --no-chapters --no-attachments --no-global-tags ^
 --disable-track-statistics-tags --title "" ^
 "video.hdr10.hevc" ^
 --no-video "step1_cut.mkv"
if errorlevel 1 goto :fail

REM 5) Convert to MP4: keep video + AC3 only, no subs, no metadata
ffmpeg ^
 -i "step2_hdr10.mkv" ^
 -map 0:v:0 -map 0:a:0 ^
 -c:v copy -c:a copy ^
 -map_metadata -1 -map_chapters -1 -dn -sn ^
 -tag:v hvc1 -movflags +faststart ^
 "The.Godfather.1972_02h35m00s_to_02h40m00s_HDR10_AC3_ENG.mp4"
if errorlevel 1 goto :fail

REM Optional cleanup
del "video.dv.hevc" 2>nul
del "video.hdr10.hevc" 2>nul
del "video_timecodes.txt" 2>nul
del "step1_cut.mkv" 2>nul
del "step2_hdr10.mkv" 2>nul

echo Done
popd
exit /b 0

:fail
echo Something failed. Check the command just above for the error message.
popd
exit /b 1
