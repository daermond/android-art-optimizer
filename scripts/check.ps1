$ErrorActionPreference = "Stop"
if (Test-Path ./gradlew.bat) {
    $gradle = "./gradlew.bat"
} elseif (Get-Command gradle -ErrorAction SilentlyContinue) {
    $gradle = "gradle"
} else {
    throw "Gradle wrapper/local Gradle missing. Restore the committed Gradle wrapper."
}
& $gradle --no-daemon lint testDebugUnitTest assembleDebug
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
