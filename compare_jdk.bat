@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM ============================================
REM  JDK API 对比脚本 (Windows)
REM  用法:
REM    compare_jdk.bat                -> 运行全部对比 (17vs21, 21vs25)
REM    compare_jdk.bat 17 21          -> 只对比 JDK 17 vs 21
REM    compare_jdk.bat 21 25          -> 只对比 JDK 21 vs 25
REM  注意:
REM    1. 请先修改下方 JDK 路径，指向你的实际安装位置
REM    2. 运行此脚本需 JDK 26 (或 17+) 来启动 apidiff
REM ============================================

REM ---- 修改这里的路径 ----
set JDK17=C:\Program Files\Java\jdk-17
set JDK21=C:\Program Files\Java\jdk-21
set JDK25=C:\Program Files\Java\jdk-25

REM 用来运行 apidiff 的 JDK (≥17)
set JDK_RUN=%JDK21%\bin\java
REM ------------------------

set APIDIFF_JAR=%~dp0lib\apidiff.jar

if not exist "%JDK_RUN%" (
    echo [错误] 找不到运行用 JDK: %JDK_RUN%
    echo        请修改 JDK_RUN 变量，指向 JDK 17+ 的 bin\java.exe
    exit /b 1
)

if not exist "%APIDIFF_JAR%" (
    echo [错误] 找不到 apidiff.jar: %APIDIFF_JAR%
    echo        请确保此 bat 和 lib\apidiff.jar 在同一目录
    exit /b 1
)

set ARG1=%1
set ARG2=%2

if "%ARG1%"=="" (
    set RUN17_21=1
    set RUN21_25=1
) else (
    if "%ARG1%%ARG2%"=="1721" ( set RUN17_21=1 )
    if "%ARG1%%ARG2%"=="2117" ( set RUN17_21=1 )
    if "%ARG1%%ARG2%"=="2125" ( set RUN21_25=1 )
    if "%ARG1%%ARG2%"=="2521" ( set RUN21_25=1 )
)

if defined RUN17_21 (
    echo.
    echo ========================================
    echo  比较 JDK 17 vs JDK 21
    echo ========================================
    if not exist "%JDK17%" (
        echo [错误] JDK 17 路径不存在: %JDK17%
    ) else if not exist "%JDK21%" (
        echo [错误] JDK 21 路径不存在: %JDK21%
    ) else (
        "%JDK_RUN%" -jar "%APIDIFF_JAR%" ^
            --api JDK17 --system "%JDK17%" ^
            --api JDK21 --system "%JDK21%" ^
            --include "java.**" --include "jdk.**" ^
            --title "JDK 17 → 21 API 变化" ^
            -d "%~dp0report\17vs21"
        echo 报告生成完毕: %~dp0report\17vs21\index.html
    )
)

if defined RUN21_25 (
    echo.
    echo ========================================
    echo  比较 JDK 21 vs JDK 25
    echo ========================================
    if not exist "%JDK21%" (
        echo [错误] JDK 21 路径不存在: %JDK21%
    ) else if not exist "%JDK25%" (
        echo [错误] JDK 25 路径不存在: %JDK25%
    ) else (
        "%JDK_RUN%" -jar "%APIDIFF_JAR%" ^
            --api JDK21 --system "%JDK21%" ^
            --api JDK25 --system "%JDK25%" ^
            --include "java.**" --include "jdk.**" ^
            --title "JDK 21 → 25 API 变化" ^
            -d "%~dp0report\21vs25"
        echo 报告生成完毕: %~dp0report\21vs25\index.html
    )
)

echo.
echo 全部完成!
pause
