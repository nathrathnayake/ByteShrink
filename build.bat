@echo off
setlocal
cd /d "%~dp0"
if exist build rmdir /s /q build
mkdir build\classes
if not exist build\sources.txt type nul > build\sources.txt
for /r src\main\java %%f in (*.java) do echo "%%f">> build\sources.txt
javac --release 17 -encoding UTF-8 -d build\classes @build\sources.txt
if errorlevel 1 exit /b 1
jar --create --file build\byteshrink.jar --main-class dev.nath.byteshrink.ArchiveMateApp -C build\classes .
if errorlevel 1 exit /b 1
echo Built build\byteshrink.jar
