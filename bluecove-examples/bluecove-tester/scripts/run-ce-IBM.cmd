@echo off
rem @version $Revision$ ($Author$)  $Date$
SETLOCAL

call %~dp0environment.cmd %*
if errorlevel 1 (
    echo Error calling environment.cmd
    endlocal
    pause
    exit /b 1
)

%WMDPT%\RAPI_Start\rapistart "\Program Files\J9\PPRO10\bin\j9.exe" -jcl:ppro10 -Dmicroedition.connection.pkgs=com.intel.bluetooth -cp "%BLUECOVE_INSTALL_DIR%\bluecove-tester.jar" net.sf.bluecove.awt.Main

rem %WMDPT%\RAPI_Start\rapistart "\bluecove\BlueCove-IBM"

if errorlevel 1 goto errormark
echo [Launched OK]
goto endmark
:errormark
	ENDLOCAL
	echo Error in start
	pause
:endmark
ENDLOCAL
