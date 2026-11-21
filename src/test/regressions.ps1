# Requires PowerShell 7, a built installDist, JDK 25, Gradle 9.3.1 and Zig.
# On JDK 21 use -Releases 21. All subprocesses use the JDK selected by PATH.
param(
    [string]$Zig = "$PSScriptRoot/../../.tooling/zig-x86_64-windows-0.15.2/zig.exe",
    [string]$GradleHome = "$env:USERPROFILE/.gradle/wrapper/dists/gradle-9.3.1-bin/23ovyewtku6u96viwx3xl3oks/gradle-9.3.1",
    [int[]]$Releases = @(21,25),
    [ValidateSet('off','balanced','maximum')]
    [string]$KotlinAnalysisProfile = 'off'
)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath("$PSScriptRoot/../..")
$work = Join-Path $root ('build/regressions-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
[IO.Directory]::CreateDirectory($work) | Out-Null
$java = (Get-Command java.exe).Source
$jdk = Split-Path (Split-Path $java)
$librarySnapshot = Join-Path $work 'libraries'
[IO.Directory]::CreateDirectory($librarySnapshot) | Out-Null
Copy-Item -Path "$root/build/install/native-obfuscator/lib/*.jar" -Destination $librarySnapshot
$libs = "$librarySnapshot/*"
$asm = "$librarySnapshot/asm-9.9.jar"
$Zig = [IO.Path]::GetFullPath($Zig)
$commands = Join-Path $work 'commands.jsonl'
$summary = [Collections.Generic.List[object]]::new()
function Run([string]$exe, [string[]]$arguments, [string]$label, [bool]$expectFailure = $false) {
    $p = [Diagnostics.ProcessStartInfo]::new($exe)
    $p.UseShellExecute = $false
    $p.RedirectStandardOutput = $true
    $p.RedirectStandardError = $true
    foreach ($argument in $arguments) { $p.ArgumentList.Add($argument) }
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $process = [Diagnostics.Process]::Start($p)
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    $stdout = $stdoutTask.GetAwaiter().GetResult()
    $stderr = $stderrTask.GetAwaiter().GetResult()
    [IO.File]::WriteAllText((Join-Path $work "$label.stdout.txt"),$stdout)
    [IO.File]::WriteAllText((Join-Path $work "$label.stderr.txt"),$stderr)
    @{ executable=$exe; arguments=$arguments; exitCode=$process.ExitCode; milliseconds=$timer.ElapsedMilliseconds; label=$label } |
        ConvertTo-Json -Compress -Depth 10 | Add-Content -LiteralPath $commands
    if (($expectFailure -and $process.ExitCode -eq 0) -or (!$expectFailure -and $process.ExitCode -ne 0)) {
        $tail = ($stderr -split "`n" | Select-Object -Last 12) -join "`n"
        throw "$label exit $($process.ExitCode); see $work/$label.stderr.txt`n$tail"
    }
    return $stdout
}
function Tool([string[]]$arguments, [string]$label, [bool]$expectFailure=$false) {
    Run $java (@('-cp',$libs,'dev.nativeobfuscator.Main')+$arguments) $label $expectFailure
}
function Assert([bool]$condition,[string]$message) { if (!$condition) { throw $message } }
function Jar([string]$file,[string]$classes,[string]$main,[string]$label) {
    $arguments = @('--create','--file',$file)
    if ($main) { $arguments += @('--main-class',$main) }
    Run "$jdk/bin/jar.exe" ($arguments+@('-C',$classes,'.')) $label | Out-Null
}
function ExecuteJar([string]$file,[string[]]$arguments,[string]$label) {
    Run $java (@('-Xverify:all','-Xcheck:jni','--enable-native-access=ALL-UNNAMED','-jar',$file)+$arguments) $label
}
function RewriteZip([string]$source,[string]$destination,[string]$mode) {
    $input = [IO.Compression.ZipFile]::OpenRead($source)
    $output = [IO.Compression.ZipFile]::Open($destination,[IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($entry in $input.Entries) {
            $memory = [IO.MemoryStream]::new()
            $stream = $entry.Open()
            try { $stream.CopyTo($memory) } finally { $stream.Dispose() }
            $data = $memory.ToArray()
            if (($mode -eq 'native' -and $entry.FullName.EndsWith('.dll')) -or
                ($mode -eq 'registration' -and $entry.FullName.Contains('/registration/'))) {
                $data[$data.Length-1] = $data[$data.Length-1] -bxor 1
            }
            $out = $output.CreateEntry($entry.FullName).Open()
            try { $out.Write($data) } finally { $out.Dispose() }
        }
    } finally { $input.Dispose(); $output.Dispose() }
}
foreach ($invalid in @('duplicate','traversal','signed','malformed')) {
    $archive = Join-Path $work "invalid-$invalid.jar"
    $zip = [IO.Compression.ZipFile]::Open($archive,[IO.Compression.ZipArchiveMode]::Create)
    try {
        $names = switch ($invalid) {
            duplicate { @('same','same') }
            traversal { @('../outside') }
            signed { @('META-INF/SIGNER.SF') }
            malformed { @('Arithmetic.class') }
        }
        foreach ($name in $names) {
            $stream = $zip.CreateEntry($name).Open()
            try { $stream.Write([Text.Encoding]::UTF8.GetBytes('invalid fixture')) } finally { $stream.Dispose() }
        }
    } finally { $zip.Dispose() }
    Tool @('analyze','--input',$archive,'--include','Arithmetic#calc*') "reject-archive-$invalid" $true | Out-Null
    $expected = switch ($invalid) {
        duplicate { 'Duplicate JAR entry' }
        traversal { 'Unsafe JAR entry name' }
        signed { 'Signed input JAR' }
        malformed { 'classfile' }
    }
    Assert ([IO.File]::ReadAllText((Join-Path $work "reject-archive-$invalid.stderr.txt")).Contains($expected)) "Wrong rejection for $invalid archive"
}
foreach ($release in $Releases) {
    $classes = Join-Path $work "arithmetic-$release"
    [IO.Directory]::CreateDirectory($classes) | Out-Null
    Run "$jdk/bin/javac.exe" @('--release',"$release",'-Xlint:all','-Werror','-d',$classes,"$PSScriptRoot/fixtures/Arithmetic.java") "arithmetic-$release-compile" | Out-Null
    $original = Join-Path $work "arithmetic-$release.jar"
    $protected = Join-Path $work "arithmetic-$release-native.jar"
    Jar $original $classes 'Arithmetic' "arithmetic-$release-jar"
    $baseline = ExecuteJar $original @() "arithmetic-$release-java"
    $rows = Tool @('protect','--input',$original,'--output',$protected,'--include','Arithmetic#calc*','--unsupported','retain','--zig',$Zig) "arithmetic-$release-protect"
    $native = ExecuteJar $protected @() "arithmetic-$release-native"
    Assert ($baseline -ceq $native) "Arithmetic $release output differs"
    $decisions = @($rows -split "`n" | Where-Object { $_.Trim() } | ForEach-Object { $_ | ConvertFrom-Json })
    Assert (@($decisions | Where-Object result -eq selected).Count -eq 19) 'Expected 19 native arithmetic methods'
    Assert (@($decisions | Where-Object result -eq retained).Count -eq 2) 'Expected lambda and explicit throw retained'
    $rejected = Join-Path $work "arithmetic-$release-rejected.jar"
    [IO.File]::WriteAllText($rejected,'existing output')
    Tool @('protect','--input',$original,'--output',$rejected,'--include','Arithmetic#calc*','--zig',$Zig) "arithmetic-$release-strict" $true | Out-Null
    Assert ([IO.File]::ReadAllText($rejected) -eq 'existing output') 'Strict failure overwrote existing output'
    foreach ($mode in @('native','registration')) {
        $tampered = Join-Path $work "arithmetic-$release-tampered-$mode.jar"
        RewriteZip $protected $tampered $mode
        Run $java @('-Xcheck:jni','--enable-native-access=ALL-UNNAMED','-jar',$tampered) "arithmetic-$release-reject-$mode" $true | Out-Null
        $expected = if ($mode -eq 'native') { 'Native library checksum mismatch' } else { 'Native method registration failed' }
        Assert ([IO.File]::ReadAllText((Join-Path $work "arithmetic-$release-reject-$mode.stderr.txt")).Contains($expected)) "Wrong rejection for tampered $mode"
    }
    $summary.Add(@{test='arithmetic'; release=$release; nativeMethods=19; digest=$native.Trim(); target='x86_64-windows'; executed=$true})

    $generator = Join-Path $work "generator-$release"
    $kernelClasses = Join-Path $work "kernels-$release"
    [IO.Directory]::CreateDirectory($generator) | Out-Null
    [IO.Directory]::CreateDirectory($kernelClasses) | Out-Null
    Run "$jdk/bin/javac.exe" @('--release',"$release",'-cp',$asm,'-d',$generator,"$PSScriptRoot/generated/GenerateKernels.java") "kernels-$release-compile-generator" | Out-Null
    Run "$jdk/bin/javac.exe" @('--release',"$release",'-d',$kernelClasses,"$PSScriptRoot/generated/CheckKernels.java") "kernels-$release-compile-checker" | Out-Null
    foreach ($seed in @(12345,90475)) {
        Run $java @('-cp',"$generator;$asm",'GenerateKernels',$kernelClasses,"$release","$seed") "kernels-$release-$seed-generate" | Out-Null
        $original = Join-Path $work "kernels-$release-$seed.jar"
        Jar $original $kernelClasses 'CheckKernels' "kernels-$release-$seed-jar"
        $baseline = ExecuteJar $original @('java') "kernels-$release-$seed-java"
        foreach ($flavor in @('protected','replicated')) {
            $output = Join-Path $work "kernels-$release-$seed-$flavor.jar"
            Tool @('protect','--input',$original,'--output',$output,'--include','GeneratedKernels#calc*','--zig',$Zig) "kernels-$release-$seed-$flavor-build" | Out-Null
        }
        $protected = Join-Path $work "kernels-$release-$seed-protected.jar"
        $replicated = Join-Path $work "kernels-$release-$seed-replicated.jar"
        Assert ((Get-FileHash -LiteralPath $protected).Hash -eq (Get-FileHash -LiteralPath $replicated).Hash) 'Kernel builds differ'
        $native = ExecuteJar $protected @('native') "kernels-$release-$seed-native"
        Assert ($native -ceq $baseline) 'Generated kernel results differ'
        $summary.Add(@{test='generated'; release=$release; seed=$seed; calls=60000; digest=$native.Trim(); deterministic=$true; target='x86_64-windows'; executed=$true})
    }

    $modern = Join-Path $work "modern-$release"
    [IO.Directory]::CreateDirectory($modern) | Out-Null
    Run "$jdk/bin/javac.exe" @('--release','21','-encoding','UTF-8','-cp',$asm,'-d',$generator,"$PSScriptRoot/modern/GenerateCondy.java") "modern-$release-compile-generator" | Out-Null
    Run $java @('-cp',"$generator;$asm",'GenerateCondy',"$release",$modern) "modern-$release-generate" | Out-Null
    Run "$jdk/bin/javac.exe" @('--release',"$release",'-encoding','UTF-8','-d',$modern,"$PSScriptRoot/modern/VerifyModern.java") "modern-$release-compile-checker" | Out-Null
    $original = Join-Path $work "modern-$release.jar"
    $protected = Join-Path $work "modern-$release-native.jar"
    Jar $original $modern 'modern.VerifyModern' "modern-$release-jar"
    $baseline = ExecuteJar $original @('original') "modern-$release-java"
    $selection = @('--include','modern/CondyFixture#calc*','--include','modern/VerifyModern$BridgeFixture#calcRecord*','--include','modern/VerifyModern$BridgeFixture#get*')
    Tool (@('protect','--input',$original,'--output',$protected,'--unsupported','retain','--zig',$Zig)+$selection) "modern-$release-protect" | Out-Null
    $native = ExecuteJar $protected @('protected') "modern-$release-native"
    Assert ($baseline -ceq $native) 'Modern classfile results differ'
    $summary.Add(@{test='modern';release=$release;output=$native.Trim();target='x86_64-windows';executed=$true})
}

$kotlin = Join-Path $work 'kotlin'
[IO.Directory]::CreateDirectory($kotlin) | Out-Null
$stdlib = Join-Path $GradleHome 'lib/kotlin-stdlib-2.2.21.jar'
$reflect = Join-Path $GradleHome 'lib/kotlin-reflect-2.2.21.jar'
Assert (Test-Path -LiteralPath $stdlib) 'Missing Kotlin 2.2.21 stdlib in Gradle distribution'
Run $java @('-cp',"$GradleHome/lib/*",'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-jvm-target','21','-Xlambdas=indy','-no-stdlib','-no-reflect','-classpath',"$stdlib;$reflect",'-module-name','native_obfuscator_fixture','-Werror','-d',$kotlin,"$PSScriptRoot/kotlin/KotlinFixture.kt") 'kotlin-compile' | Out-Null
$original = Join-Path $work 'kotlin.jar'
$protected = Join-Path $work 'kotlin-native.jar'
Jar $original $kotlin '' 'kotlin-jar'
$baseline = Run $java @('-Xverify:all','-cp',"$original;$stdlib;$reflect",'fixture.kotlin.KotlinFixtureKt','original') 'kotlin-java'
$kotlinOptions = @('--include','fixture/kotlin/KotlinFixture#calc*','--include','fixture/kotlin/KotlinFixtureKt#calcStatic*',
    '--unsupported','retain','--analysis-resistance',$KotlinAnalysisProfile)
if ($KotlinAnalysisProfile -ne 'off') { $kotlinOptions += @('--analysis-seed','42') }
Tool (@('protect','--input',$original,'--output',$protected,'--zig',$Zig) + $kotlinOptions) 'kotlin-protect' | Out-Null
Tool (@('verify','--input',$original,'--staged',$protected) + $kotlinOptions) 'kotlin-verify' | Out-Null
$native = Run $java @('-Xverify:all','-Xcheck:jni','--enable-native-access=ALL-UNNAMED','-cp',"$protected;$stdlib;$reflect",'fixture.kotlin.KotlinFixtureKt','protected') 'kotlin-native'
Assert ($baseline -ceq $native) 'Kotlin results differ'
$summary.Add(@{test='kotlin';version='2.2.21';output=$native.Trim();target='x86_64-windows';executed=$true;
    analysisProfile=$KotlinAnalysisProfile})
$summary | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $work 'summary.json')
Write-Host "Regression checks passed. Evidence: $work"
