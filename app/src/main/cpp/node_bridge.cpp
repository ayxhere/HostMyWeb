// Minimal JNI glue for nodejs-mobile (digidem libnode.so), adapted for IMAYX Host.
// Forwards the AyX Group backend (SQLite build, pure-JS deps) into the app process.
// We forward-declare node::Start instead of including node.h/v8.h, so there is
// no heavy header compilation to go wrong. If the linker reports an undefined
// reference to node::Start, that symbol name is the one thing to adjust.

#include <jni.h>
#include <string>
#include <vector>
#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include <pthread.h>
#include <android/log.h>

#define TAG "AYX_NODE"

namespace node { int Start(int argc, char** argv); }

static int   s_pfd[2];
static bool  s_logging = false;
static bool  s_started = false;   // node::Start may run only ONCE per OS process
static FILE* s_logfile = nullptr; // tee of node stdout/stderr for diagnostics

static void* log_pump(void*) {
    ssize_t rdsz;
    char buf[2048];
    while ((rdsz = read(s_pfd[0], buf, sizeof(buf) - 1)) > 0) {
        if (rdsz > 0 && buf[rdsz - 1] == '\n') rdsz--;
        buf[rdsz] = '\0';
        __android_log_write(ANDROID_LOG_INFO, TAG, buf);
        if (s_logfile) {
            fprintf(s_logfile, "%s\n", buf);
            fflush(s_logfile);
        }
    }
    return nullptr;
}

static void start_logging() {
    if (s_logging) return;
    s_logging = true;
    setvbuf(stdout, nullptr, _IOLBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);
    pipe(s_pfd);
    dup2(s_pfd[1], STDOUT_FILENO);
    dup2(s_pfd[1], STDERR_FILENO);
    pthread_t t;
    pthread_create(&t, nullptr, log_pump, nullptr);
    pthread_detach(t);
}

extern "C" JNIEXPORT void JNICALL
Java_com_imayx_host_node_NodeBridge_nativeSetenv(JNIEnv* env, jclass, jstring key, jstring val) {
    const char* k = env->GetStringUTFChars(key, nullptr);
    const char* v = env->GetStringUTFChars(val, nullptr);
    setenv(k, v, 1);
    env->ReleaseStringUTFChars(key, k);
    env->ReleaseStringUTFChars(val, v);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_imayx_host_node_NodeBridge_nativeStart(JNIEnv* env, jclass, jobjectArray args, jstring workdir) {
    // Hard guard: a second node::Start in one process trips a Node assertion
    // and SIGABRTs the entire app. Refuse instead of crashing.
    if (s_started) {
        __android_log_write(ANDROID_LOG_ERROR, TAG,
            "node::Start called twice in one process — refusing (would SIGABRT)");
        if (s_logfile) {
            fprintf(s_logfile, "[bridge] refused second node::Start in one process\n");
            fflush(s_logfile);
        }
        return -99;
    }
    s_started = true;

    const char* wd = env->GetStringUTFChars(workdir, nullptr);
    chdir(wd);

    // Tee node output to <workdir>/node.log for on-device diagnostics.
    s_logfile = fopen("node.log", "a");
    if (s_logfile) {
        fprintf(s_logfile, "\n[bridge] node starting in %s\n", wd);
        fflush(s_logfile);
    }
    env->ReleaseStringUTFChars(workdir, wd);

    start_logging();

    int argc = env->GetArrayLength(args);
    std::vector<std::string> store;
    store.reserve(argc);
    for (int i = 0; i < argc; i++) {
        auto s = (jstring) env->GetObjectArrayElement(args, i);
        const char* c = env->GetStringUTFChars(s, nullptr);
        store.emplace_back(c);
        env->ReleaseStringUTFChars(s, c);
        env->DeleteLocalRef(s);
    }
    std::vector<char*> argv;
    argv.reserve(argc + 1);
    for (auto& s : store) argv.push_back(const_cast<char*>(s.c_str()));
    argv.push_back(nullptr);

    return (jint) node::Start(argc, argv.data());
}
