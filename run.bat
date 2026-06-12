@echo off
REM Build and run the NES emulator without Gradle, using only a JDK 21+.
REM Usage: run.bat [path\to\rom.nes]
setlocal
set DIR=%~dp0
set OUT=%DIR%out
if not exist "%OUT%" mkdir "%OUT%"
echo Compiling...
dir /s /b "%DIR%src\main\java\*.java" > "%OUT%\sources.txt"
javac -d "%OUT%" @"%OUT%\sources.txt"
echo Launching...
java -cp "%OUT%" com.nesemu.Main %*
endlocal
