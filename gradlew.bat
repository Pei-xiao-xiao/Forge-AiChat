@echo off
setlocal

REM Gradle Wrapper Script - Simple Version
REM This script directly uses the installed Gradle

set JAVA_HOME=C:\Program Files\Java\jdk-17
set PATH=%JAVA_HOME%\bin;%PATH%

set GRADLE_HOME=C:\Users\admin\.gradle\wrapper\dists\gradle-8.11.1-bin\eac4u065zwes5phgltp5f9b9e\gradle-8.11.1
set PATH=%GRADLE_HOME%\bin;%PATH%

set JAVA_OPTS=-Djava.net.preferIPv4Stack=true -Djava.net.preferIPv6Addresses=false
set GRADLE_OPTS=-Dorg.gradle.daemon=false -Dorg.gradle.jvmargs=-Xmx1G -Djava.net.preferIPv4Stack=true -Djava.net.preferIPv6Addresses=false

gradle %*

endlocal
