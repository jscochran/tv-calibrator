@echo off
rem TV Calibrator lab tool on Windows. Double-click this file, or run it from a terminal with options:
rem   "Start on Windows.cmd" --sim                         demo with a simulated TV and meter
rem   "Start on Windows.cmd" --port 8766 --no-browser      another port, no browser window
setlocal
set PYTHONUTF8=1

rem ArgyllCMS: if its bin folder isn't on PATH, put it here (remove "rem" and fix the folder):
rem set "PATH=C:\Argyll_V3.5.0\bin;%PATH%"
where spotread >nul 2>nul
if errorlevel 1 (
  for /d %%D in ("C:\Argyll_V*" "%USERPROFILE%\Argyll_V*" "%ProgramFiles%\Argyll_V*" "%USERPROFILE%\Downloads\Argyll_V*") do (
    if exist "%%~D\bin\spotread.exe" set "ARGYLL_BIN=%%~D\bin"
  )
)
if defined ARGYLL_BIN set "PATH=%ARGYLL_BIN%;%PATH%"

if not exist "%USERPROFILE%\tvcal" mkdir "%USERPROFILE%\tvcal"

set "APP=%~dp0TV Calibrator.app\Contents\Resources\tvcal_app.py"
where py >nul 2>nul
if errorlevel 1 (
  python "%APP%" %*
) else (
  py -3 "%APP%" %*
)
if errorlevel 1 pause
endlocal
