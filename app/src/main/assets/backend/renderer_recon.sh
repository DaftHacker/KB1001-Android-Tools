#!/system/bin/sh

TARGET="$1"
OUT="$2"
BACKEND="/data/local/kb1001perf/backend"
VERSION="/data/local/kb1001perf/backend.version"

[ -n "$OUT" ] || OUT="/data/local/tmp/kb1001_renderer_recon.txt"

foreground_package(){
 line="$(dumpsys window displays 2>/dev/null | grep -m1 -E 'mCurrentFocus=Window\\{|mFocusedApp=ActivityRecord\\{')"
 out="$(printf '%s\n' "$line" | sed -n 's/.* u[0-9][0-9]* \\([^/ }]*\\)\/.*/\\1/p')"
 if [ -z "$out" ]; then
  line="$(dumpsys activity activities 2>/dev/null | grep -m1 -E 'topResumedActivity=ActivityRecord|mResumedActivity: ActivityRecord')"
  out="$(printf '%s\n' "$line" | sed -n 's/.* u[0-9][0-9]* \\([^/ }]*\\)\/.*/\\1/p')"
 fi
 printf '%s\n' "$out"
}

[ -n "$TARGET" ] || TARGET="$(foreground_package)"

{
 echo "KB1001 A333 raw renderer / injection recon"
 echo "device=KB1001"
 echo "soc=Allwinner A333"
 echo "platform=sun65iw1p1"
 echo "cpu_topology=4x Cortex-A53 + 1x Cortex-A73"
 echo "gpu_stock_max_mhz=696"
 echo "date=$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null)"
 echo "target=$TARGET"
 echo "foreground=$(foreground_package)"
 echo "identity=$(id 2>/dev/null)"
 echo "selinux=$(getenforce 2>/dev/null)"
 echo "build_type=$(getprop ro.build.type)"
 echo "ro_debuggable=$(getprop ro.debuggable)"
 echo "ro_secure=$(getprop ro.secure)"
 echo "sdk=$(getprop ro.build.version.sdk)"
 echo "fingerprint=$(getprop ro.build.fingerprint)"
 echo "backend_version=$(cat "$VERSION" 2>/dev/null)"
 if command -v sha256sum >/dev/null 2>&1; then
  echo "fps_sampler_sha256=$(sha256sum "$BACKEND/fps_sampler.sh" 2>/dev/null | awk '{print $1}')"
  echo "renderer_recon_sha256=$(sha256sum "$0" 2>/dev/null | awk '{print $1}')"
 fi

 echo
 echo "=== target package flags ==="
 dumpsys package "$TARGET" 2>/dev/null | grep -E -i 'Package \\[|userId=|pkgFlags=|privateFlags=|DEBUGGABLE|profileable|nativeLibraryDir|primaryCpuAbi|secondaryCpuAbi|extractNativeLibs|seInfo=' | head -n 120

 echo
 echo "=== GPU layer settings ==="
 for k in enable_gpu_debug_layers gpu_debug_app gpu_debug_layers gpu_debug_layers_gles gpu_debug_layer_app; do
  echo "$k=$(settings get global "$k" 2>/dev/null)"
 done
 echo "debug.gles.layers=$(getprop debug.gles.layers)"
 echo "debug.vulkan.layers=$(getprop debug.vulkan.layers)"
 echo "debug.egl.hw=$(getprop debug.egl.hw)"
 echo "debug.hwui.renderer=$(getprop debug.hwui.renderer)"

 echo
 echo "=== GPU service ==="
 cmd gpu help 2>&1 | head -n 120
 echo "--- dumpsys gpu ---"
 dumpsys gpu 2>&1 | head -n 240

 echo
 echo "=== layer search locations ==="
 for d in /data/local/debug/gles /data/local/debug/vulkan /system/lib64 /vendor/lib64; do
  echo "--- $d ---"
  ls -la "$d" 2>&1 | head -n 120
 done

 echo
 echo "=== target processes and loaded renderer libraries ==="
 pids="$(pidof "$TARGET" 2>/dev/null)"
 echo "pids=$pids"
 for pid in $pids; do
  echo
  echo "--- pid=$pid ---"
  tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null; echo
  grep -E '^(Name|Tgid|Pid|PPid|TracerPid|Uid|Gid|NoNewPrivs|Seccomp|CapEff):' "/proc/$pid/status" 2>/dev/null
  echo "context=$(cat "/proc/$pid/attr/current" 2>/dev/null)"
  echo "--- graphics/app maps ---"
  grep -E -i 'lib(EGL|GLES|vulkan|angle|hwui|fpsvalidator|unity|UE4|Unreal|game|main).*\\.so|gralloc|mali|GLES' "/proc/$pid/maps" 2>/dev/null | head -n 240
 done

 echo
 echo "=== present entrypoints in platform libraries ==="
 if command -v readelf >/dev/null 2>&1; then
  for lib in /system/lib64/libEGL.so /system/lib64/libvulkan.so /system/lib64/libGLESv2.so; do
   echo "--- $lib ---"
   readelf -Ws "$lib" 2>/dev/null | grep -E 'eglSwapBuffers|eglSwapBuffersWithDamage|vkQueuePresentKHR|AndroidGLESLayer_' | head -n 80
  done
 elif command -v nm >/dev/null 2>&1; then
  for lib in /system/lib64/libEGL.so /system/lib64/libvulkan.so /system/lib64/libGLESv2.so; do
   echo "--- $lib ---"
   nm -D "$lib" 2>/dev/null | grep -E 'eglSwapBuffers|eglSwapBuffersWithDamage|vkQueuePresentKHR|AndroidGLESLayer_' | head -n 80
  done
 else
  echo "readelf/nm unavailable"
 fi

 echo
 echo "=== Magisk / Zygisk ==="
 command -v magisk 2>/dev/null || true
 magisk -V 2>/dev/null || true
 magisk --path 2>/dev/null || true
 magisk --sqlite 'SELECT key,value FROM settings WHERE key="zygisk";' 2>/dev/null || true
 for z in $(pidof zygote64 zygote 2>/dev/null); do
  echo "--- zygote pid=$z zygisk maps ---"
  grep -i 'zygisk\|magisk' "/proc/$z/maps" 2>/dev/null | head -n 80
 done
 echo "ptrace_scope=$(cat /proc/sys/kernel/yama/ptrace_scope 2>/dev/null)"

 echo
 echo "=== SurfaceFlinger target layers ==="
 dumpsys SurfaceFlinger --list 2>/dev/null | grep -Fi "$TARGET" | head -n 100

 echo
 echo "=== current FPS sampler diagnosis ==="
 sh "$BACKEND/fps_sampler.sh" diagnose 2>&1 | head -n 360

} > "$OUT" 2>&1

chmod 0644 "$OUT" 2>/dev/null
echo "$OUT"
