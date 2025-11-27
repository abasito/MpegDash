@echo off
setlocal

REM ======================================================
REM  FOR TEST DUR = 297.9
REM  FOR TRAIN DUR = 398
REM ======================================================

set BASENAME=avatar_train
set DUR=398
set OUTDIR=..\dash_%BASENAME%
set "OUT=ladder_%BASENAME%_h264_sdr"
set "SRC=%BASENAME%_h264_aac_SDR.mp4"

REM ======================================================
REM  HEVC CONVERSION
REM ======================================================

call :send_discord "%BASENAME%: STARTED!"

ffmpeg ^
  -i "%BASENAME%.mp4" ^
  -map 0:v:0 -map 0:a:0 -map_metadata 0 ^
  -c:v h264_nvenc ^
  -preset slow ^
  -profile:v high -level:v 5.1 ^
  -rc:v vbr_hq -cq:v 18 -qmin:v 0 -qmax:v 18 -b:v 0 -maxrate:v 60M -bufsize:v 120M ^
  -fps_mode:v passthrough ^
  -force_key_frames "source" ^
  -g 9999 -keyint_min 9999 ^
  -rc-lookahead 0 -no-scenecut 1 -forced-idr 1 ^
  -vf "zscale=t=linear:npl=100,format=gbrpf32le,zscale=p=bt709,tonemap=tonemap=hable:desat=0,zscale=t=bt709:m=bt709:r=tv,format=yuv420p" ^
  -pix_fmt yuv420p ^
  -color_primaries bt709 -colorspace bt709 -color_trc bt709 ^
  -c:a aac -b:a 640k -ac 6 -ar 48000 ^
  -movflags +faststart ^
  "%BASENAME%_h264_aac_SDR.mp4"

echo H.264 conversion done!
call :send_discord "%BASENAME%: H.264 conversion done!"

REM ======================================================
REM  RES LADDER
REM ======================================================
REM ======================================================
REM  CONFIG
REM ======================================================
REM Your new master file (HDR HEVC, AC-3 5.1)

REM Output folder
if not exist "%OUT%" mkdir "%OUT%"

REM ======================================================
REM  ORIGINAL VIDEO-ONLY COPY (STREAM COPY, HEVC)
REM  (Keeps your original HEVC video untouched for archival)
REM ======================================================
echo === Making original video-only copy (stream copy) ===

ffmpeg -y -i "%SRC%" ^
  -map 0:v:0 ^
  -c copy ^
  "%OUT%\%BASENAME%_2160p_original_video_h264.mp4"
if errorlevel 1 goto :err

call :send_discord "%BASENAME%: RESLADDER OG video done!"

REM ======================================================
REM  AUDIO-ONLY EXTRACT (AC-3 5.1 -> AAC 5.1)
REM ======================================================
echo.
echo === Extracting audio-only to AAC 5.1 ===

ffmpeg -y -i "%SRC%" ^
  -map 0:a:0 ^
  -c:a aac -b:a 640k -ac 6 -ar 48000 ^
  "%OUT%\%BASENAME%_audio_aac_5_1.m4a"
if errorlevel 1 goto :err

call :send_discord "%BASENAME%: RESLADDER Audio done!"


REM ======================================================
REM  VIDEO LADDER (VIDEO-ONLY, NVENC H.264 SDR)
REM  - HDR10 (BT.2020/PQ) -> SDR BT.709 via zscale + tonemap (Hable)
REM ======================================================
echo.
echo === Encoding ladder (video-only, H.264 NVENC, HDR->SDR, fps_mode passthrough) ===
echo.

call :encode 2160 20000k
call :send_discord "%BASENAME%: RESLADDER 2160p 20000k done!"

call :encode 1440 10000k
call :send_discord "%BASENAME%: RESLADDER 1440p 10000k done!"

call :encode 1080 7000k
call :send_discord "%BASENAME%: RESLADDER 1080p 7000k done!"

call :encode 720  4000k
call :send_discord "%BASENAME%: RESLADDER 720p 4000k done!"

call :encode 480  1500k
call :send_discord "%BASENAME%: RESLADDER 480p 1500k done!"

call :encode 360  800k
call :send_discord "%BASENAME%: RESLADDER 360p 800k done!"

call :encode 240  400k
call :send_discord "%BASENAME%: RESLADDER 240p 400k done!"

call :encode 144  160k
call :send_discord "%BASENAME%: RESLADDER 144p 160k done!"

echo.
echo All encodes finished.
call :send_discord "%BASENAME%: RESLADDER Fully done!"

REM ======================================================
REM  ERROR HANDLER
REM ======================================================
:err
echo.
echo ERROR: something failed. Check the last ffmpeg output above.

exit /b 1

echo RES LADDER DONE!

REM ======================================================
REM  DASHER
REM ======================================================

cd ladder_%BASENAME%_h264_sdr

MP4Box ^
 -dash 2000 ^
 -rap -frag-rap ^
 -profile live ^
 -segment-ext m4s ^
 -segment-name "segments/$RepresentationID$/$Init=init$av$RepresentationID$$Number$" ^
 -out %OUTDIR%\%BASENAME%.mpd ^
 %BASENAME%_144p_160k_h264_sdr.mp4#video:dur=297.9:id=v144 ^
 %BASENAME%_240p_400k_h264_sdr.mp4#video:dur=297.9:id=v240 ^
 %BASENAME%_360p_800k_h264_sdr.mp4#video:dur=297.9:id=v360 ^
 %BASENAME%_480p_1500k_h264_sdr.mp4#video:dur=297.9:id=v480 ^
 %BASENAME%_720p_4000k_h264_sdr.mp4#video:dur=297.9:id=v720 ^
 %BASENAME%_1080p_7000k_h264_sdr.mp4#video:dur=297.9:id=v1080 ^
 %BASENAME%_1440p_10000k_h264_sdr.mp4#video:dur=297.9:id=v1440 ^
 %BASENAME%_2160p_20000k_h264_sdr.mp4#video:dur=297.9:id=v2160 ^
 %BASENAME%_2160p_original_video_h264.mp4#video:dur=297.9:id=v2160orig ^
 %BASENAME%_audio_aac_5_1.m4a#audio:dur=297.9:id=a_ac3

echo DASHER done!
call :send_discord "%BASENAME%: DASHER done!"

pause

REM ======================================================
REM  NOTIFICATION FUNCTION
REM ======================================================

:send_discord
if "%~1"=="" goto :eof
powershell -ExecutionPolicy Bypass -File ".\send_discord.ps1" -content "%~1"
goto :eof

REM ======================================================
REM  ENCODE FUNCTION
REM  %1 = HEIGHT  (e.g. 1080)
REM  %2 = BITRATE (e.g. 7000k)
REM ======================================================
:encode
set "HEIGHT=%1"
set "BITRATE=%2"

echo %HEIGHT%p @ %BITRATE% ...

ffmpeg -y ^
  -i "%SRC%" ^
  -map 0:v:0 ^
  -vf "zscale=t=linear:npl=100,format=gbrpf32le,zscale=p=bt709,tonemap=tonemap=hable:desat=0,zscale=t=bt709:m=bt709:r=tv,scale=-2:%HEIGHT%,format=yuv420p" ^
  ^
  -c:v h264_nvenc ^
  -preset p5 ^
  -profile:v high ^
  -pix_fmt yuv420p ^
  -b:v %BITRATE% -maxrate %BITRATE% -bufsize %BITRATE% ^
  -fps_mode:v passthrough ^
  -force_key_frames source ^
  -g 9999 -keyint_min 9999 ^
  -rc-lookahead 0 -no-scenecut 1 -forced-idr 1 ^
  -an ^
  "%OUT%\%BASENAME%_%HEIGHT%p_%BITRATE%_h264_sdr.mp4"

if errorlevel 1 goto :err
echo Done %HEIGHT%p.
echo.
goto :eof



