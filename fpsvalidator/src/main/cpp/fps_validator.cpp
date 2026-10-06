#include <jni.h>
#include <android/native_window_jni.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <deque>
#include <fstream>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <time.h>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "KB1001FpsValidator", __VA_ARGS__)

namespace {

constexpr int MODE_FIXED = 0;
constexpr int MODE_SWEEP = 1;
constexpr int MODE_STEP = 2;
constexpr int MODE_JITTER = 3;
constexpr int MODE_STALL = 4;
constexpr int MODE_AUTO = 5;

std::atomic<bool> g_running{false};
std::thread g_thread;
std::mutex g_statusMutex;
std::string g_status = "Idle";

using PFNEGLGETNEXTFRAMEIDANDROIDPROC_LOCAL =
        EGLBoolean (EGLAPIENTRYP)(EGLDisplay, EGLSurface, EGLuint64KHR*);
using PFNEGLGETFRAMETIMESTAMPSANDROIDPROC_LOCAL =
        EGLBoolean (EGLAPIENTRYP)(EGLDisplay, EGLSurface, EGLuint64KHR, EGLint,
                                  const EGLint*, EGLnsecsANDROID*);

struct PendingFrame {
    uint64_t seq{};
    EGLuint64KHR eglFrameId{};
    double targetFps{};
    int mode{};
    int phase{};
    int64_t targetNs{};
    int64_t renderStartNs{};
    int64_t renderEndNs{};
    int64_t swapStartNs{};
    int64_t swapEndNs{};
};

int64_t monoNs() {
    timespec ts{};
    clock_gettime(CLOCK_BOOTTIME, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

void sleepUntilNs(int64_t targetNs) {
    for (;;) {
        const int64_t now = monoNs();
        if (now >= targetNs) return;
        const int64_t remain = targetNs - now;
        if (remain > 2'000'000) {
            std::this_thread::sleep_for(std::chrono::nanoseconds(remain - 1'000'000));
        } else {
            std::this_thread::yield();
        }
    }
}

void setStatus(const std::string& s) {
    std::lock_guard<std::mutex> lock(g_statusMutex);
    g_status = s;
}

double targetFor(int mode,
                 double fixed,
                 double elapsedSec,
                 int& phase,
                 int& effectiveMode,
                 double& patternElapsed,
                 bool& done) {
    phase = 0;
    effectiveMode = mode;
    patternElapsed = elapsedSec;
    done = false;

    if (mode == MODE_AUTO) {
        // One-click ~60 second regression suite.
        // 0-4s: compositor/overlay warm-up. This interval is intentionally
        // excluded from scored fixed plateaus so the normal game hook can
        // discover the validator and start its overlay/logger first.
        if (elapsedSec < 4.0) {
            effectiveMode = MODE_FIXED;
            phase = 0;
            patternElapsed = elapsedSec;
            return 60.0;
        }

        // 4-22s: fixed-rate accuracy plateaus, 2 seconds each.
        static const double fixedRates[] = {10, 15, 20, 24, 30, 40, 45, 50, 60};
        if (elapsedSec < 22.0) {
            effectiveMode = MODE_FIXED;
            const double local = elapsedSec - 4.0;
            const int idx = std::min(8, static_cast<int>(local / 2.0));
            phase = 10 + idx;
            patternElapsed = local - idx * 2.0;
            return fixedRates[idx];
        }

        // 22-32s: continuous 10 -> 60 -> 10 sweep.
        if (elapsedSec < 32.0) {
            effectiveMode = MODE_SWEEP;
            const double local = elapsedSec - 22.0;
            phase = 30 + static_cast<int>(local);
            patternElapsed = local;
            const double x = local <= 5.0 ? local / 5.0 : (10.0 - local) / 5.0;
            return 10.0 + 50.0 * std::max(0.0, std::min(1.0, x));
        }

        // 32-44s: abrupt rate transitions, 2 seconds each.
        static const double stepRates[] = {60, 30, 60, 20, 45, 30};
        if (elapsedSec < 44.0) {
            effectiveMode = MODE_STEP;
            const double local = elapsedSec - 32.0;
            const int idx = std::min(5, static_cast<int>(local / 2.0));
            phase = 50 + idx;
            patternElapsed = local - idx * 2.0;
            return stepRates[idx];
        }

        // 44-52s: irregular pacing around 60 FPS.
        if (elapsedSec < 52.0) {
            effectiveMode = MODE_JITTER;
            phase = 70;
            patternElapsed = elapsedSec - 44.0;
            return 60.0;
        }

        // 52-62s: deliberate 250/500 ms stalls inside a nominal 60 FPS stream.
        if (elapsedSec < 62.0) {
            effectiveMode = MODE_STALL;
            phase = 80;
            patternElapsed = elapsedSec - 52.0;
            return 60.0;
        }

        done = true;
        effectiveMode = MODE_FIXED;
        phase = 99;
        patternElapsed = 0.0;
        return 60.0;
    }

    switch (mode) {
        case MODE_SWEEP: {
            const double t = std::fmod(elapsedSec, 20.0);
            phase = static_cast<int>(t / 2.0);
            patternElapsed = t;
            const double x = t <= 10.0 ? t / 10.0 : (20.0 - t) / 10.0;
            return 10.0 + 50.0 * x;
        }
        case MODE_STEP: {
            static const double rates[] = {60, 30, 60, 20, 45, 30};
            const int idx = static_cast<int>(elapsedSec / 4.0) % 6;
            phase = idx;
            patternElapsed = std::fmod(elapsedSec, 4.0);
            return rates[idx];
        }
        case MODE_JITTER:
            phase = static_cast<int>(elapsedSec) % 2;
            return fixed > 0 ? fixed : 60.0;
        case MODE_STALL:
            phase = static_cast<int>(elapsedSec) % 8;
            patternElapsed = std::fmod(elapsedSec, 8.0);
            return fixed > 0 ? fixed : 60.0;
        default:
            return fixed > 0 ? fixed : 60.0;
    }
}

bool hasExt(const char* extensions, const char* name) {
    if (!extensions || !name) return false;
    std::string hay(extensions);
    std::string needle(name);
    size_t pos = 0;
    while ((pos = hay.find(needle, pos)) != std::string::npos) {
        const bool left = pos == 0 || hay[pos - 1] == ' ';
        const size_t end = pos + needle.size();
        const bool right = end == hay.size() || hay[end] == ' ';
        if (left && right) return true;
        pos = end;
    }
    return false;
}

void writeHeader(std::ofstream& out) {
    out << "# device=KB1001\n# soc=Allwinner A333\n# platform=sun65iw1p1\n# cpu_topology=4x Cortex-A53 + 1x Cortex-A73\n# gpu_stock_max_mhz=696\nseq,mode,phase,target_fps,target_frame_ns,render_start_ns,render_end_ns,"
           "swap_start_ns,swap_end_ns,egl_frame_id,present_supported,"
           "rendering_complete_ns,composition_latch_ns,actual_present_ns,"
           "present_delta_ns,present_fps\n";
}

void flushFrame(std::ofstream& out,
                const PendingFrame& f,
                bool presentSupported,
                int64_t renderingCompleteNs,
                int64_t compositionLatchNs,
                int64_t actualPresentNs,
                int64_t& previousPresentNs) {
    int64_t delta = -1;
    double presentFps = -1.0;
    if (actualPresentNs > 0 && previousPresentNs > 0 && actualPresentNs > previousPresentNs) {
        delta = actualPresentNs - previousPresentNs;
        presentFps = 1e9 / static_cast<double>(delta);
    }
    if (actualPresentNs > 0) previousPresentNs = actualPresentNs;

    out << f.seq << ','
        << f.mode << ','
        << f.phase << ','
        << f.targetFps << ','
        << f.targetNs << ','
        << f.renderStartNs << ','
        << f.renderEndNs << ','
        << f.swapStartNs << ','
        << f.swapEndNs << ','
        << f.eglFrameId << ','
        << (presentSupported ? 1 : 0) << ','
        << renderingCompleteNs << ','
        << compositionLatchNs << ','
        << actualPresentNs << ','
        << delta << ','
        << presentFps << '\n';
}

void renderer(ANativeWindow* window, std::string path, int mode, double fixedFps) {
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLSurface surface = EGL_NO_SURFACE;
    EGLContext context = EGL_NO_CONTEXT;

    auto cleanup = [&] {
        if (display != EGL_NO_DISPLAY) {
            eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
            if (surface != EGL_NO_SURFACE) eglDestroySurface(display, surface);
            eglTerminate(display);
        }
        if (window) ANativeWindow_release(window);
        g_running = false;
    };

    display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display == EGL_NO_DISPLAY || !eglInitialize(display, nullptr, nullptr)) {
        setStatus("EGL initialization failed");
        cleanup();
        return;
    }

    const EGLint cfgAttrs[] = {
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8,
            EGL_NONE
    };
    EGLConfig config{};
    EGLint count = 0;
    if (!eglChooseConfig(display, cfgAttrs, &config, 1, &count) || count < 1) {
        setStatus("No EGL configuration");
        cleanup();
        return;
    }

    surface = eglCreateWindowSurface(display, config, window, nullptr);
    const EGLint ctxAttrs[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    context = eglCreateContext(display, config, EGL_NO_CONTEXT, ctxAttrs);
    if (surface == EGL_NO_SURFACE || context == EGL_NO_CONTEXT ||
        !eglMakeCurrent(display, surface, surface, context)) {
        setStatus("Could not create EGL surface/context");
        cleanup();
        return;
    }

    eglSwapInterval(display, 0);

    EGLint width = 0, height = 0;
    eglQuerySurface(display, surface, EGL_WIDTH, &width);
    eglQuerySurface(display, surface, EGL_HEIGHT, &height);
    glViewport(0, 0, width, height);

    const char* exts = eglQueryString(display, EGL_EXTENSIONS);
    const bool frameTsExt = hasExt(exts, "EGL_ANDROID_get_frame_timestamps");
    auto getNextFrameId = reinterpret_cast<PFNEGLGETNEXTFRAMEIDANDROIDPROC_LOCAL>(
            eglGetProcAddress("eglGetNextFrameIdANDROID"));
    auto getFrameTimestamps = reinterpret_cast<PFNEGLGETFRAMETIMESTAMPSANDROIDPROC_LOCAL>(
            eglGetProcAddress("eglGetFrameTimestampsANDROID"));
    const bool presentSupported = frameTsExt && getNextFrameId && getFrameTimestamps;

    std::ofstream out(path, std::ios::out | std::ios::trunc);
    static thread_local char csvBuffer[256 * 1024];
    out.rdbuf()->pubsetbuf(csvBuffer, sizeof(csvBuffer));
    if (!out.is_open()) {
        setStatus("Could not open CSV");
        cleanup();
        return;
    }
    writeHeader(out);

    std::deque<PendingFrame> pending;
    uint64_t seq = 0;
    int64_t previousPresentNs = 0;
    int64_t sessionStart = monoNs();
    int64_t nextFrameNs = sessionStart;
    int64_t lastCsvFlushNs = sessionStart;

    {
        std::ostringstream s;
        s << "Running • " << (presentSupported ? "EGL actual-present timestamps available" :
                             "EGL present timestamps unavailable; swap timing only");
        setStatus(s.str());
    }

    while (g_running.load()) {
        const int64_t now = monoNs();
        const double elapsed = (now - sessionStart) / 1e9;
        int phase = 0;
        int effectiveMode = mode;
        double patternElapsed = elapsed;
        bool done = false;
        double targetFps = targetFor(
                mode, fixedFps, elapsed, phase, effectiveMode, patternElapsed, done);
        if (done) {
            setStatus("Automatic validation complete");
            break;
        }

        targetFps = std::max(1.0, std::min(240.0, targetFps));
        int64_t framePeriodNs = static_cast<int64_t>(1e9 / targetFps);

        if (effectiveMode == MODE_JITTER) {
            // Alternating +/-25% pacing while preserving approximately the requested mean.
            framePeriodNs = static_cast<int64_t>(framePeriodNs * ((seq & 1) ? 1.25 : 0.75));
        }

        if (effectiveMode == MODE_STALL) {
            // Single deliberate stall events, rather than slowing every frame in the interval.
            const double slot = std::fmod(patternElapsed, 10.0);
            if (slot >= 3.0 && slot < 3.05 && (seq % 2 == 0)) {
                framePeriodNs += 250'000'000LL;
            }
            if (slot >= 6.0 && slot < 6.05 && (seq % 2 == 1)) {
                framePeriodNs += 500'000'000LL;
            }
        }

        if (nextFrameNs < now - framePeriodNs * 2) nextFrameNs = now;
        sleepUntilNs(nextFrameNs);
        const int64_t targetNs = nextFrameNs;
        nextFrameNs += framePeriodNs;

        PendingFrame f{};
        f.seq = ++seq;
        f.targetFps = targetFps;
        f.mode = effectiveMode;
        f.phase = phase;
        f.targetNs = targetNs;

        EGLuint64KHR frameId = 0;
        if (presentSupported) getNextFrameId(display, surface, &frameId);
        f.eglFrameId = frameId;

        f.renderStartNs = monoNs();

        // GPU workload: clear plus a deterministic set of scissored clears. This is deliberately
        // simple and stable so the harness validates frame timing rather than scene complexity.
        const float t = static_cast<float>((seq % 600) / 600.0);
        glDisable(GL_SCISSOR_TEST);
        glClearColor(0.05f + t * 0.25f, 0.08f, 0.14f + (1.0f - t) * 0.25f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
        glEnable(GL_SCISSOR_TEST);
        for (int i = 0; i < 24; ++i) {
            int x = (i * 97 + static_cast<int>(seq * 3)) % std::max(1, width);
            int y = (i * 53 + static_cast<int>(seq * 2)) % std::max(1, height);
            glScissor(x, y, std::min(80, std::max(1, width - x)),
                      std::min(80, std::max(1, height - y)));
            glClearColor((i % 3) / 2.0f, ((i + 1) % 3) / 2.0f, ((i + 2) % 3) / 2.0f, 1.0f);
            glClear(GL_COLOR_BUFFER_BIT);
        }
        glDisable(GL_SCISSOR_TEST);
        glFlush();
        f.renderEndNs = monoNs();

        f.swapStartNs = monoNs();
        const EGLBoolean swapped = eglSwapBuffers(display, surface);
        f.swapEndNs = monoNs();
        if (!swapped) {
            setStatus("eglSwapBuffers failed");
            break;
        }

        if (presentSupported && f.eglFrameId != 0) {
            pending.push_back(f);
        } else {
            flushFrame(out, f, false, -1, -1, -1, previousPresentNs);
        }

        // Presentation timestamps can become available a little later than swap. Keep a small
        // queue and query old frames opportunistically without blocking the renderer.
        for (auto it = pending.begin(); it != pending.end();) {
            const EGLint names[] = {
                    EGL_RENDERING_COMPLETE_TIME_ANDROID,
                    EGL_COMPOSITION_LATCH_TIME_ANDROID,
                    EGL_DISPLAY_PRESENT_TIME_ANDROID
            };
            EGLnsecsANDROID values[] = {0, 0, 0};
            EGLBoolean ok = getFrameTimestamps(
                    display, surface, it->eglFrameId, 3, names, values);

            if (ok == EGL_TRUE && values[2] > 0) {
                flushFrame(
                        out, *it, true,
                        static_cast<int64_t>(values[0]),
                        static_cast<int64_t>(values[1]),
                        static_cast<int64_t>(values[2]),
                        previousPresentNs);
                it = pending.erase(it);
            } else if (seq - it->seq > 16) {
                // Timestamp never became available. Preserve the frame in the CSV explicitly.
                flushFrame(out, *it, true, -1, -1, -1, previousPresentNs);
                it = pending.erase(it);
            } else {
                ++it;
            }
        }

        const int64_t statusNow = monoNs();
        if (statusNow - lastCsvFlushNs >= 2'000'000'000LL) {
            out.flush();
            lastCsvFlushNs = statusNow;
        }

        if ((seq % 30) == 0) {
            std::ostringstream s;
            s.setf(std::ios::fixed);
            s.precision(1);
            s << (mode == MODE_AUTO ? "AUTO • " : "")
              << "Target " << targetFps << " FPS • phase " << phase
              << " • frame " << seq
              << " • present timestamps " << (presentSupported ? "ON" : "OFF");
            setStatus(s.str());
        }
    }

    for (const auto& f : pending) {
        flushFrame(out, f, presentSupported, -1, -1, -1, previousPresentNs);
    }
    out.flush();
    out.close();
    setStatus("Stopped • CSV saved");
    cleanup();
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dafthacker_fpsvalidator_MainActivity_nativeStart(
        JNIEnv* env, jobject, jobject surfaceObj, jstring csvPath, jint mode, jfloat targetFps) {
    if (g_running.exchange(true)) return JNI_FALSE;

    ANativeWindow* window = ANativeWindow_fromSurface(env, surfaceObj);
    if (!window) {
        g_running = false;
        return JNI_FALSE;
    }

    const char* chars = env->GetStringUTFChars(csvPath, nullptr);
    std::string path(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(csvPath, chars);

    if (g_thread.joinable()) g_thread.join();
    g_thread = std::thread(renderer, window, path, static_cast<int>(mode), targetFps);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_dafthacker_fpsvalidator_MainActivity_nativeStop(JNIEnv*, jobject) {
    g_running = false;
    if (g_thread.joinable()) g_thread.join();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_dafthacker_fpsvalidator_MainActivity_nativeStatus(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_statusMutex);
    return env->NewStringUTF(g_status.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dafthacker_fpsvalidator_MainActivity_nativeIsRunning(JNIEnv*, jobject) {
    return g_running.load() ? JNI_TRUE : JNI_FALSE;
}
