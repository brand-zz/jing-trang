@echo off
setlocal
set JING_HOME=%~d0%~p0..
%JAVA_HOME%\bin\java -Dant.home=%JING_HOME% -cp %JING_HOME%\lib\ant.jar;%JING_HOME%\lib\saxon.jar;%JING_HOME%\lib\crimson.jar;%JING_HOME%\lib\optional.jar;%JAVA_HOME%\lib\tools.jar;%JING_HOME%\lib\junit.jar;%JING_HOME%\lib\regex.jar;%JING_HOME%\lib\regex2.jar;%JING_HOME%\lib\isorelax.jar org.apache.tools.ant.Main -buildfile %JING_HOME%\build.xml %*