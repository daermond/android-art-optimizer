$ErrorActionPreference = "Stop"
if (Test-Path ./gradlew.bat) {
    $gradle = "./gradlew.bat"
} elseif (Get-Command gradle -ErrorAction SilentlyContinue) {
    $gradle = "gradle"
} else {
    throw "Gradle wrapper/local Gradle missing. Generate the wrapper with Gradle 8.9 or open the project in Android Studio."
}
& $gradle --no-daemon lint testDebugUnitTest assembleDebug
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
