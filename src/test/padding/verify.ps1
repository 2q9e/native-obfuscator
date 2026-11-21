param(
    [Parameter(Mandatory=$true)][string]$Zig,
    [string]$Gradle = "gradle.bat",
    [string]$Clang,
    [string]$Opt,
    [string]$Plugin,
    [string]$OutputDirectory = "build/padding-verification",
    [string]$Passes = "native-encode<seed=42;max=4;prefix=method_>"
)
$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "../../..")).Path
Push-Location $projectRoot
try {
    & $Gradle installDist
    if ($LASTEXITCODE -ne 0) { throw "Distribution build failed" }
    $testWork = [IO.Path]::GetFullPath((Join-Path $projectRoot $OutputDirectory))
    if (-not $testWork.StartsWith((Join-Path $projectRoot "build") + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "OutputDirectory must be inside the project build directory"
    }
    $testClasses = Join-Path $testWork "classes"
    $fixtureClasses = Join-Path $testWork "fixture"
    New-Item -ItemType Directory -Force -Path $testClasses,$fixtureClasses | Out-Null
    $runtimeJars = Get-ChildItem -LiteralPath (Join-Path $projectRoot "build/install/native-obfuscator/lib") -Filter *.jar
    if (-not $runtimeJars) {
        $runtimeJars = Get-ChildItem (Join-Path $projectRoot "build/install/*/lib/*.jar")
    }
    # Keep this run independent of later installDist rebuilds in the shared workspace.
    $runtimeSnapshot = Join-Path $testWork ("runtime-" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Path $runtimeSnapshot | Out-Null
    foreach ($runtimeJar in $runtimeJars) { Copy-Item -LiteralPath $runtimeJar.FullName -Destination $runtimeSnapshot }
    $runtimeCp = ((Get-ChildItem -LiteralPath $runtimeSnapshot -Filter *.jar).FullName -join [IO.Path]::PathSeparator)
    & javac --release 21 -cp $runtimeCp -d $testClasses (Join-Path $PSScriptRoot "PaddingPipelineTest.java") (Join-Path $PSScriptRoot "ArchiveCheck.java") (Join-Path $PSScriptRoot "MarkerWorkflowTest.java")
    if ($LASTEXITCODE -ne 0) { throw "Padding test compilation failed" }
    & javac --release 21 -d $fixtureClasses (Join-Path $projectRoot "src/test/fixtures/Arithmetic.java")
    if ($LASTEXITCODE -ne 0) { throw "Fixture compilation failed" }
    $inputJar = Join-Path $testWork "original.jar"
    & jar --create --file $inputJar --main-class Arithmetic -C $fixtureClasses .
    if ($LASTEXITCODE -ne 0) { throw "Fixture JAR failed" }
    $testCp = "$testClasses$([IO.Path]::PathSeparator)$runtimeCp"
    $driverArgs = @($inputJar,$testWork,$Zig)
    if ($Plugin) { $driverArgs += @($Clang,$Opt,$Plugin,$Passes) }
    & java -Xverify:all -Xcheck:jni --enable-native-access=ALL-UNNAMED -cp $testCp dev.nativeobfuscator.PaddingPipelineTest @driverArgs
    if ($LASTEXITCODE -ne 0) { throw "Padding pipeline verification failed" }
    & java -Xverify:all -cp $testCp dev.nativeobfuscator.MarkerWorkflowTest $inputJar (Join-Path $testWork "padding-0.jar") (Join-Path $testWork "marker-mutations")
    if ($LASTEXITCODE -ne 0) { throw "Marker/runtime archive verification failed" }
    foreach ($count in @(0,1,12,1000)) {
        & java -cp $testCp ArchiveCheck (Join-Path $testWork "padding-$count.jar") (Join-Path $testWork "inventory-$count.txt") |
            Set-Content -Encoding utf8 (Join-Path $testWork "blind-check-$count.json")
        if ($LASTEXITCODE -ne 0) { throw "Blind archive check failed" }
    }
    Write-Output "Measurements: $testWork/measurements.json"
} finally { Pop-Location }
