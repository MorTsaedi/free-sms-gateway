import subprocess
import os
import shutil
from pathlib import Path
from server.config import build_config


ANDROID_DIR = Path("/app/android")
BUILD_OUTPUT_DIR = Path("/app/static/downloads")


async def build_apk(build_config_obj) -> str:
    BUILD_OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    
    local_props = ANDROID_DIR / "local.properties"
    with open(local_props, "w") as f:
        f.write(f"sdk.dir=/opt/android-sdk\n")
        f.write(f"vm.url={build_config_obj.vm_url}\n")
        f.write(f"vm.api_key={build_config_obj.vm_api_key}\n")
        f.write(f"app.package_name={build_config_obj.package_name}\n")
        f.write(f"app.app_name={build_config_obj.app_name}\n")
        f.write(f"app.poll_interval_seconds={build_config_obj.poll_interval_seconds}\n")
        f.write(f"app.heartbeat_interval_seconds={build_config_obj.heartbeat_interval_seconds}\n")
    
    gradlew = ANDROID_DIR / "gradlew"
    if not gradlew.exists():
        raise RuntimeError("gradlew not found. Run gradle wrapper setup first.")
    
    gradlew.chmod(0o755)
    
    env = os.environ.copy()
    env["JAVA_HOME"] = "/usr/lib/jvm/java-17-openjdk-amd64"
    env["ANDROID_SDK_ROOT"] = "/opt/android-sdk"
    env["PATH"] = f"{env['PATH']}:{env['ANDROID_SDK_ROOT']}/cmdline-tools/latest/bin:{env['ANDROID_SDK_ROOT']}/platform-tools"
    
    result = subprocess.run(
        ["./gradlew", "assembleDebug"],
        cwd=ANDROID_DIR,
        env=env,
        capture_output=True,
        text=True,
        timeout=300,
    )
    
    if result.returncode != 0:
        raise RuntimeError(f"APK build failed: {result.stderr}")
    
    apk_src = ANDROID_DIR / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
    if not apk_src.exists():
        raise RuntimeError("APK not found after build")
    
    import time
    timestamp = int(time.time())
    apk_dst = BUILD_OUTPUT_DIR / f"sms-gateway-{timestamp}.apk"
    shutil.copy2(apk_src, apk_dst)
    
    return str(apk_dst)