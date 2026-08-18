#include <algorithm>
#include <android/api-level.h>
#include <android/dlext.h>
#include <android/log.h>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <elf.h>
#include <jni.h>
#include <optional>
#include <string>
#include <string_view>
#include <sys/resource.h>
#include <unistd.h>
#include <utility>
#include <vector>

#if defined(__aarch64__)
#include <adrenotools/driver.h>
#include <adrenotools/priv.h>
#endif

struct RPCSXApi {
  bool (*overlayPadData)(int port, int digital1, int digital2, int leftStickX,
                         int leftStickY, int rightStickX, int rightStickY);
  bool (*overlayPadPressure)(int port, const int *values, int count);
  bool (*initialize)(std::string_view rootDir, std::string_view user);
  void (*setSocInfo)(std::string_view socInfo);
  bool (*processCompilationQueue)(JNIEnv *env);
  bool (*startMainThreadProcessor)(JNIEnv *env);
  bool (*collectGameInfo)(JNIEnv *env, std::string_view rootDir,
                          long progressId);
  void (*shutdown)();
  int (*boot)(std::string_view path_);
  int (*getState)();
  void (*kill)();
  void (*resume)();
  void (*pause)();
  void (*openHomeMenu)();
  std::string (*getTitleId)();
  unsigned long long (*getFramePeriodNs)();
  unsigned long long (*getFrameWorkNs)();
  int (*getRsxThreadTid)();
  std::string (*getCurrentTrophyName)();
  bool (*surfaceEvent)(JNIEnv *env, jobject surface, jint event);
  void (*surfaceSizeChanged)(int width, int height);
  void (*setPadSensor)(int port, int x, int y, int z, int g);
  int (*getPadRumble)(int port);
  bool (*usbDeviceEvent)(int fd, int vendorId, int productId, int event);
  bool (*installFw)(JNIEnv *env, int fd, long progressId);
  bool (*isInstallableFile)(jint fd);
  jstring (*getDirInstallPath)(JNIEnv *env, jint fd);
  bool (*install)(JNIEnv *env, int fd, long progressId);
  bool (*installKey)(JNIEnv *env, int fd, long progressId,
                     std::string_view gamePath);
  std::string (*systemInfo)();
  void (*loginUser)(std::string_view userId);
  std::string (*getUser)();
  std::string (*settingsGet)(std::string_view path);
  bool (*settingsSet)(std::string_view path, std::string_view valueString);
  int (*frameGenImportShaders)(std::string_view path);
  int (*frameGenShaderCount)();
  const char *(*frameGenShaderError)();
  void (*settingsBeginBatch)();
  void (*settingsEndBatch)();
  bool (*installSplitPkg)(JNIEnv *env, const int *fds, int count, long progressId);
  bool (*uninstallGame)(std::string_view path);
  std::string (*getVersion)();
  void *(*setCustomDriver)(void *driverHandle);
  void (*reportDriverProblem)(std::string message);
  bool (*saveState)();
  bool (*loadState)(unsigned int index);
  bool (*hasState)(unsigned int index);
  // Numbered slots, distinct from the three above: those address RPCS3's rolling
  // history by age, these address a fixed slot. Resolved separately so an older
  // core without them degrades to the history rather than failing to load.
  bool (*saveStateToSlot)(unsigned int slot);
  bool (*loadStateFromSlot)(unsigned int slot);
  bool (*hasStateInSlot)(unsigned int slot);
  std::string (*patchEngineVersion)();
  int (*patchesImport)(std::string_view content);
  std::string (*patchesList)(std::string_view serial);
  std::string (*probeDiscInfo)(std::string_view isoPath, std::string_view iconOut);
  bool (*patchSetEnabled)(std::string_view hash, std::string_view description,
                          std::string_view serial, std::string_view appVersion,
                          bool enabled);
};

struct RPCSXLibrary : RPCSXApi {
  void *handle = nullptr;

  RPCSXLibrary() = default;
  RPCSXLibrary(const RPCSXLibrary &) = delete;
  RPCSXLibrary(RPCSXLibrary &&other) { swap(other); }
  RPCSXLibrary &operator=(RPCSXLibrary &&other) {
    swap(other);
    return *this;
  }
  ~RPCSXLibrary() {
    if (handle) {
      ::dlclose(handle);
    }
  }

  void swap(RPCSXLibrary &other) noexcept {
    std::swap(handle, other.handle);
    std::swap(static_cast<RPCSXApi &>(*this), static_cast<RPCSXApi &>(other));
  }

  static std::optional<RPCSXLibrary> Open(const char *path) {
    void *handle = ::dlopen(path, RTLD_LOCAL | RTLD_NOW);
    if (handle == nullptr) {
      __android_log_print(ANDROID_LOG_ERROR, "RPCSX-UI",
                          "Failed to open RPCSX library at %s, error %s", path,
                          ::dlerror());
      return {};
    }

    RPCSXLibrary result;
    result.handle = handle;

    // clang-format off
    result.overlayPadData = reinterpret_cast<decltype(overlayPadData)>(dlsym(handle, "_rpcsx_overlayPadData"));
    result.overlayPadPressure = reinterpret_cast<decltype(overlayPadPressure)>(dlsym(handle, "_rpcsx_overlayPadPressure"));
    result.initialize = reinterpret_cast<decltype(initialize)>(dlsym(handle, "_rpcsx_initialize"));
    result.setSocInfo = reinterpret_cast<decltype(setSocInfo)>(dlsym(handle, "_rpcsx_setSocInfo"));
    result.processCompilationQueue = reinterpret_cast<decltype(processCompilationQueue)>(dlsym(handle, "_rpcsx_processCompilationQueue"));
    result.startMainThreadProcessor = reinterpret_cast<decltype(startMainThreadProcessor)>(dlsym(handle, "_rpcsx_startMainThreadProcessor"));
    result.collectGameInfo = reinterpret_cast<decltype(collectGameInfo)>(dlsym(handle, "_rpcsx_collectGameInfo"));
    result.shutdown = reinterpret_cast<decltype(shutdown)>(dlsym(handle, "_rpcsx_shutdown"));
    result.boot = reinterpret_cast<decltype(boot)>(dlsym(handle, "_rpcsx_boot"));
    result.getState = reinterpret_cast<decltype(getState)>(dlsym(handle, "_rpcsx_getState"));
    result.kill = reinterpret_cast<decltype(kill)>(dlsym(handle, "_rpcsx_kill"));
    result.resume = reinterpret_cast<decltype(resume)>(dlsym(handle, "_rpcsx_resume"));
    result.pause = reinterpret_cast<decltype(pause)>(dlsym(handle, "_rpcsx_pause"));
    result.openHomeMenu = reinterpret_cast<decltype(openHomeMenu)>(dlsym(handle, "_rpcsx_openHomeMenu"));
    result.getTitleId = reinterpret_cast<decltype(getTitleId)>(dlsym(handle, "_rpcsx_getTitleId"));
    result.getFramePeriodNs = reinterpret_cast<decltype(getFramePeriodNs)>(dlsym(handle, "_rpcsx_getFramePeriodNs"));
    result.getFrameWorkNs = reinterpret_cast<decltype(getFrameWorkNs)>(dlsym(handle, "_rpcsx_getFrameWorkNs"));
    result.getRsxThreadTid = reinterpret_cast<decltype(getRsxThreadTid)>(dlsym(handle, "_rpcsx_getRsxThreadTid"));
    result.getCurrentTrophyName = reinterpret_cast<decltype(getCurrentTrophyName)>(dlsym(handle, "_rpcsx_getCurrentTrophyName"));
    result.surfaceEvent = reinterpret_cast<decltype(surfaceEvent)>(dlsym(handle, "_rpcsx_surfaceEvent"));
    result.surfaceSizeChanged = reinterpret_cast<decltype(surfaceSizeChanged)>(dlsym(handle, "_rpcsx_surfaceSizeChanged"));
    result.setPadSensor = reinterpret_cast<decltype(setPadSensor)>(dlsym(handle, "_rpcsx_setPadSensor"));
    result.getPadRumble = reinterpret_cast<decltype(getPadRumble)>(dlsym(handle, "_rpcsx_getPadRumble"));
    result.usbDeviceEvent = reinterpret_cast<decltype(usbDeviceEvent)>(dlsym(handle, "_rpcsx_usbDeviceEvent"));
    result.installFw = reinterpret_cast<decltype(installFw)>(dlsym(handle, "_rpcsx_installFw"));
    result.isInstallableFile = reinterpret_cast<decltype(isInstallableFile)>(dlsym(handle, "_rpcsx_isInstallableFile"));
    result.getDirInstallPath = reinterpret_cast<decltype(getDirInstallPath)>(dlsym(handle, "_rpcsx_getDirInstallPath"));
    result.install = reinterpret_cast<decltype(install)>(dlsym(handle, "_rpcsx_install"));
    result.installKey = reinterpret_cast<decltype(installKey)>(dlsym(handle, "_rpcsx_installKey"));
    result.systemInfo = reinterpret_cast<decltype(systemInfo)>(dlsym(handle, "_rpcsx_systemInfo"));
    result.loginUser = reinterpret_cast<decltype(loginUser)>(dlsym(handle, "_rpcsx_loginUser"));
    result.getUser = reinterpret_cast<decltype(getUser)>(dlsym(handle, "_rpcsx_getUser"));
    result.settingsGet = reinterpret_cast<decltype(settingsGet)>(dlsym(handle, "_rpcsx_settingsGet"));
    result.settingsSet = reinterpret_cast<decltype(settingsSet)>(dlsym(handle, "_rpcsx_settingsSet"));
    // Resolved without ensure(): a core built before frame generation existed simply has no such
    // symbol, and refusing to load it over a missing optional feature would be worse than the
    // feature being absent. The Kotlin side treats a null here as "unsupported".
    result.frameGenImportShaders = reinterpret_cast<decltype(frameGenImportShaders)>(dlsym(handle, "_rpcsx_frameGenImportShaders"));
    result.frameGenShaderCount = reinterpret_cast<decltype(frameGenShaderCount)>(dlsym(handle, "_rpcsx_frameGenShaderCount"));
    result.frameGenShaderError = reinterpret_cast<decltype(frameGenShaderError)>(dlsym(handle, "_rpcsx_frameGenShaderError"));
    result.settingsBeginBatch = reinterpret_cast<decltype(settingsBeginBatch)>(dlsym(handle, "_rpcsx_settingsBeginBatch"));
    result.settingsEndBatch = reinterpret_cast<decltype(settingsEndBatch)>(dlsym(handle, "_rpcsx_settingsEndBatch"));
    result.installSplitPkg = reinterpret_cast<decltype(installSplitPkg)>(dlsym(handle, "_rpcsx_installSplitPkg"));
    result.uninstallGame = reinterpret_cast<decltype(uninstallGame)>(dlsym(handle, "_rpcsx_uninstallGame"));
    result.getVersion = reinterpret_cast<decltype(getVersion)>(dlsym(handle, "_rpcsx_getVersion"));
    result.setCustomDriver = reinterpret_cast<decltype(setCustomDriver)>(dlsym(handle, "_rpcsx_setCustomDriver"));
    result.reportDriverProblem = reinterpret_cast<decltype(reportDriverProblem)>(dlsym(handle, "_rpcsx_reportDriverProblem"));
    result.saveState = reinterpret_cast<decltype(saveState)>(dlsym(handle, "_rpcsx_saveState"));
    result.loadState = reinterpret_cast<decltype(loadState)>(dlsym(handle, "_rpcsx_loadState"));
    result.hasState = reinterpret_cast<decltype(hasState)>(dlsym(handle, "_rpcsx_hasState"));
    result.saveStateToSlot = reinterpret_cast<decltype(saveStateToSlot)>(dlsym(handle, "_rpcsx_saveStateToSlot"));
    result.loadStateFromSlot = reinterpret_cast<decltype(loadStateFromSlot)>(dlsym(handle, "_rpcsx_loadStateFromSlot"));
    result.hasStateInSlot = reinterpret_cast<decltype(hasStateInSlot)>(dlsym(handle, "_rpcsx_hasStateInSlot"));
    result.patchEngineVersion = reinterpret_cast<decltype(patchEngineVersion)>(dlsym(handle, "_rpcsx_patchEngineVersion"));
    result.patchesImport = reinterpret_cast<decltype(patchesImport)>(dlsym(handle, "_rpcsx_patchesImport"));
    result.patchesList = reinterpret_cast<decltype(patchesList)>(dlsym(handle, "_rpcsx_patchesList"));
    result.probeDiscInfo = reinterpret_cast<decltype(probeDiscInfo)>(dlsym(handle, "_rpcsx_probeDiscInfo"));
    result.patchSetEnabled = reinterpret_cast<decltype(patchSetEnabled)>(dlsym(handle, "_rpcsx_patchSetEnabled"));
    // clang-format on

    return result;
  }
};

static RPCSXLibrary rpcsxLib;

static std::string unwrap(JNIEnv *env, jstring string) {
  auto resultBuffer = env->GetStringUTFChars(string, nullptr);
  std::string result(resultBuffer);
  env->ReleaseStringUTFChars(string, resultBuffer);
  return result;
}
static jstring wrap(JNIEnv *env, const std::string &string) {
  return env->NewStringUTF(string.c_str());
}
static jstring wrap(JNIEnv *env, const char *string) {
  return env->NewStringUTF(string);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_openLibrary(JNIEnv *env, jobject, jstring path) {
  if (auto library = RPCSXLibrary::Open(unwrap(env, path).c_str())) {
    rpcsxLib = std::move(*library);
    return true;
  }

  return false;
}

extern "C" JNIEXPORT jstring JNICALL
Java_net_rpcsx_RPCSX_getLibraryVersion(JNIEnv *env, jobject, jstring path) {
  if (auto library = RPCSXLibrary::Open(unwrap(env, path).c_str())) {
    if (auto getVersion = library->getVersion) {
      return wrap(env, getVersion());
    }
  }

  return {};
}

extern "C" JNIEXPORT jboolean JNICALL Java_net_rpcsx_RPCSX_overlayPadData(
    JNIEnv *, jobject, jint port, jint digital1, jint digital2, jint leftStickX,
    jint leftStickY, jint rightStickX, jint rightStickY) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (rpcsxLib.overlayPadData == nullptr) {
      return false;
  }

  return rpcsxLib.overlayPadData(port, digital1, digital2, leftStickX,
                                 leftStickY, rightStickX, rightStickY);
}

extern "C" JNIEXPORT jboolean JNICALL Java_net_rpcsx_RPCSX_overlayPadPressure(
    JNIEnv *env, jobject, jint port, jintArray values) {
  // Absent on a core older than this export: the pad still works, every button
  // is just digital, which is the behaviour that shipped before it existed.
  if (rpcsxLib.overlayPadPressure == nullptr || values == nullptr) {
    return false;
  }

  const jsize count = env->GetArrayLength(values);
  if (count <= 0) {
    return false;
  }

  // Critical rather than a copy: this runs on every input event that moves a
  // trigger, and the callee only reads the values before returning.
  auto *elems = static_cast<jint *>(env->GetPrimitiveArrayCritical(values, nullptr));
  if (elems == nullptr) {
    return false;
  }

  static_assert(sizeof(jint) == sizeof(int),
                "jint and int must match for the pressure array to be passed through");
  const bool ok = rpcsxLib.overlayPadPressure(
      port, reinterpret_cast<const int *>(elems), static_cast<int>(count));

  env->ReleasePrimitiveArrayCritical(values, elems, JNI_ABORT);
  return ok;
}

extern "C" JNIEXPORT jboolean JNICALL Java_net_rpcsx_RPCSX_initialize(
    JNIEnv *env, jobject, jstring rootDir, jstring user, jstring socInfo) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (rpcsxLib.initialize == nullptr) {
      return false;
  }

  // Before initialize(), which is where the core assembles its startup log.
  // Null on cores older than this export; the SoC line then reads "unknown".
  if (rpcsxLib.setSocInfo != nullptr) {
      rpcsxLib.setSocInfo(unwrap(env, socInfo));
  }

  return rpcsxLib.initialize(unwrap(env, rootDir), unwrap(env, user));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_processCompilationQueue(JNIEnv *env, jobject) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (rpcsxLib.processCompilationQueue == nullptr) {
      return false;
  }

  return rpcsxLib.processCompilationQueue(env);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_startMainThreadProcessor(JNIEnv *env, jobject) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (rpcsxLib.startMainThreadProcessor == nullptr) {
      return false;
  }

  return rpcsxLib.startMainThreadProcessor(env);
}

extern "C" JNIEXPORT jboolean JNICALL Java_net_rpcsx_RPCSX_collectGameInfo(
    JNIEnv *env, jobject, jstring jrootDir, jlong progressId) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (rpcsxLib.collectGameInfo == nullptr) {
      return false;
  }

  return rpcsxLib.collectGameInfo(env, unwrap(env, jrootDir), progressId);
}

extern "C" JNIEXPORT void JNICALL Java_net_rpcsx_RPCSX_shutdown(JNIEnv *env,
                                                                jobject) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (rpcsxLib.shutdown == nullptr) {
      return;
  }

  return rpcsxLib.shutdown();
}

extern "C" JNIEXPORT jint JNICALL Java_net_rpcsx_RPCSX_boot(JNIEnv *env,
                                                            jobject,
                                                            jstring jpath) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (rpcsxLib.boot == nullptr) {
      return 0;
  }

  return rpcsxLib.boot(unwrap(env, jpath));
}

extern "C" JNIEXPORT jint JNICALL Java_net_rpcsx_RPCSX_getState(JNIEnv *env,
                                                                jobject) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (rpcsxLib.getState == nullptr) {
      return 0;
  }

  return rpcsxLib.getState();
}

extern "C" JNIEXPORT void JNICALL Java_net_rpcsx_RPCSX_kill(JNIEnv *env,
                                                            jobject) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (rpcsxLib.kill == nullptr) {
      return;
  }

  return rpcsxLib.kill();
}

extern "C" JNIEXPORT void JNICALL Java_net_rpcsx_RPCSX_resume(JNIEnv *env,
                                                              jobject) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (rpcsxLib.resume == nullptr) {
      return;
  }

  return rpcsxLib.resume();
}

extern "C" JNIEXPORT void JNICALL Java_net_rpcsx_RPCSX_pause(JNIEnv *env,
                                                             jobject) {
  // Same null guard as resume: the core is dlopen()ed separately and may not be
  // up yet. A missing symbol also means an older core, so an app built against
  // this cannot assume the export is there.
  if (rpcsxLib.pause == nullptr) {
      return;
  }

  return rpcsxLib.pause();
}

extern "C" JNIEXPORT void JNICALL Java_net_rpcsx_RPCSX_openHomeMenu(JNIEnv *env,
                                                                    jobject) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (rpcsxLib.openHomeMenu == nullptr) {
      return;
  }

  return rpcsxLib.openHomeMenu();
}

extern "C" JNIEXPORT jstring JNICALL
Java_net_rpcsx_RPCSX_getTitleId(JNIEnv *env, jobject) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (rpcsxLib.getTitleId == nullptr) {
      return nullptr;
  }

  return wrap(env, rpcsxLib.getTitleId());
}

extern "C" JNIEXPORT jstring JNICALL
Java_net_rpcsx_RPCSX_getCurrentTrophyName(JNIEnv *env, jobject) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  //
  // Also null on an OLDER core that predates this export, since it is resolved
  // by dlsym: the frontend must treat null as "unknown", not as "no trophies".
  if (rpcsxLib.getCurrentTrophyName == nullptr) {
      return nullptr;
  }

  return wrap(env, rpcsxLib.getCurrentTrophyName());
}

extern "C" JNIEXPORT jboolean JNICALL Java_net_rpcsx_RPCSX_surfaceEvent(
    JNIEnv *env, jobject, jobject surface, jint event) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (rpcsxLib.surfaceEvent == nullptr) {
      return false;
  }

  return rpcsxLib.surfaceEvent(env, surface, event);
}

extern "C" JNIEXPORT void JNICALL Java_net_rpcsx_RPCSX_setPadSensor(
    JNIEnv *, jobject, jint port, jint x, jint y, jint z, jint g) {
  if (rpcsxLib.setPadSensor == nullptr) {
      return;
  }

  rpcsxLib.setPadSensor(port, x, y, z, g);
}

extern "C" JNIEXPORT jint JNICALL Java_net_rpcsx_RPCSX_getPadRumble(
    JNIEnv *, jobject, jint port) {
  if (rpcsxLib.getPadRumble == nullptr) {
      return 0;
  }

  return rpcsxLib.getPadRumble(port);
}

extern "C" JNIEXPORT void JNICALL Java_net_rpcsx_RPCSX_surfaceSizeChanged(
    JNIEnv *, jobject, jint width, jint height) {
  if (rpcsxLib.surfaceSizeChanged == nullptr) {
      return;
  }

  rpcsxLib.surfaceSizeChanged(width, height);
}

extern "C" JNIEXPORT jboolean JNICALL Java_net_rpcsx_RPCSX_usbDeviceEvent(
    JNIEnv *env, jobject, jint fd, jint vendorId, jint productId, jint event) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (rpcsxLib.usbDeviceEvent == nullptr) {
      return false;
  }

  return rpcsxLib.usbDeviceEvent(fd, vendorId, productId, event);
}

extern "C" JNIEXPORT jboolean JNICALL Java_net_rpcsx_RPCSX_installFw(
    JNIEnv *env, jobject, jint fd, jlong progressId) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (rpcsxLib.installFw == nullptr) {
      return false;
  }

  return rpcsxLib.installFw(env, fd, progressId);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_isInstallableFile(JNIEnv *env, jobject, jint fd) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (rpcsxLib.isInstallableFile == nullptr) {
      return false;
  }

  return rpcsxLib.isInstallableFile(fd);
}

extern "C" JNIEXPORT jstring JNICALL
Java_net_rpcsx_RPCSX_getDirInstallPath(JNIEnv *env, jobject, jint fd) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (rpcsxLib.getDirInstallPath == nullptr) {
      return nullptr;
  }

  return rpcsxLib.getDirInstallPath(env, fd);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_install(JNIEnv *env, jobject, jint fd, jlong progressId) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (rpcsxLib.install == nullptr) {
      return false;
  }

  return rpcsxLib.install(env, fd, progressId);
}

extern "C" JNIEXPORT jboolean JNICALL Java_net_rpcsx_RPCSX_installKey(
    JNIEnv *env, jobject, jint fd, jlong progressId, jstring gamePath) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (rpcsxLib.installKey == nullptr) {
      return false;
  }

  return rpcsxLib.installKey(env, fd, progressId, unwrap(env, gamePath));
}

extern "C" JNIEXPORT jstring JNICALL
Java_net_rpcsx_RPCSX_systemInfo(JNIEnv *env, jobject) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (rpcsxLib.systemInfo == nullptr) {
      return nullptr;
  }

  return wrap(env, rpcsxLib.systemInfo());
}

extern "C" JNIEXPORT void JNICALL
Java_net_rpcsx_RPCSX_loginUser(JNIEnv *env, jobject, jstring user_id) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (rpcsxLib.loginUser == nullptr) {
      return;
  }

  return rpcsxLib.loginUser(unwrap(env, user_id));
}

extern "C" JNIEXPORT jstring JNICALL Java_net_rpcsx_RPCSX_getUser(JNIEnv *env,
                                                                  jobject) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (rpcsxLib.getUser == nullptr) {
      return nullptr;
  }

  return wrap(env, rpcsxLib.getUser());
}

extern "C" JNIEXPORT jstring JNICALL
Java_net_rpcsx_RPCSX_settingsGet(JNIEnv *env, jobject, jstring jpath) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (rpcsxLib.settingsGet == nullptr) {
      return nullptr;
  }

  return wrap(env, rpcsxLib.settingsGet(unwrap(env, jpath)));
}

extern "C" JNIEXPORT jboolean JNICALL Java_net_rpcsx_RPCSX_settingsSet(
    JNIEnv *env, jobject, jstring jpath, jstring jvalue) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (rpcsxLib.settingsSet == nullptr) {
      return false;
  }

  return rpcsxLib.settingsSet(unwrap(env, jpath), unwrap(env, jvalue));
}

// Defer the config file write until endBatch. Null-safe like the rest: an older core
// .so simply has no such symbol, and every settingsSet then saves as it always did.
extern "C" JNIEXPORT void JNICALL
Java_net_rpcsx_RPCSX_settingsBeginBatch(JNIEnv *, jobject) {
  if (rpcsxLib.settingsBeginBatch != nullptr) {
    rpcsxLib.settingsBeginBatch();
  }
}

extern "C" JNIEXPORT void JNICALL
Java_net_rpcsx_RPCSX_settingsEndBatch(JNIEnv *, jobject) {
  if (rpcsxLib.settingsEndBatch != nullptr) {
    rpcsxLib.settingsEndBatch();
  }
}

extern "C" JNIEXPORT jboolean JNICALL Java_net_rpcsx_RPCSX_installSplitPkg(
    JNIEnv *env, jobject, jintArray jfds, jlong progressId) {
  if (rpcsxLib.installSplitPkg == nullptr || jfds == nullptr) {
    return false;
  }

  const jsize count = env->GetArrayLength(jfds);
  if (count <= 0) {
    return false;
  }

  // Copy out rather than pinning: the install blocks for minutes, and holding a
  // critical/pinned array across that would fight the GC the whole time.
  std::vector<int> fds(static_cast<std::size_t>(count));
  env->GetIntArrayRegion(jfds, 0, count, reinterpret_cast<jint *>(fds.data()));

  return rpcsxLib.installSplitPkg(env, fds.data(), static_cast<int>(count),
                                  static_cast<long>(progressId));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_uninstallGame(JNIEnv *env, jobject, jstring jpath) {
  if (rpcsxLib.uninstallGame == nullptr) {
    return false;
  }

  return rpcsxLib.uninstallGame(unwrap(env, jpath));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_supportsCustomDriverLoading(JNIEnv *env,
                                                 jobject instance) {
  return access("/dev/kgsl-3d0", F_OK) == 0;
}

// Force the Adreno GPU to its maximum clocks, or release it back to normal scaling.
//
// Adreno's DVFS ramps clocks up only after it has already seen load, so a scene that suddenly
// becomes GPU-bound stutters through the ramp every time it happens. Pinning the clocks removes
// that at the cost of heat and battery, which is why it is opt-in rather than a default.
//
// Nothing here talks to the emulator core: adrenotools is linked into this JNI library, and
// adrenotools_set_turbo opens /dev/kgsl-3d0 itself and silently does nothing on non-Adreno
// hardware or if the open fails. So it is safe to call unconditionally, including before the core
// is loaded. Ported from the RPCSX Android fork, which ships it default-off; kept default-off here
// for the same reason.
extern "C" JNIEXPORT void JNICALL
Java_net_rpcsx_RPCSX_setGpuTurbo(JNIEnv *, jobject, jboolean on) {
#if defined(__aarch64__)
  adrenotools_set_turbo(on == JNI_TRUE);
#else
  (void) on;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_net_rpcsx_RPCSX_getVersion(JNIEnv *env, jobject) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (rpcsxLib.getVersion == nullptr) {
      return nullptr;
  }

  return wrap(env, rpcsxLib.getVersion());
}

#if defined(__aarch64__)
// Why a driver will not load, when the answer is knowable before trying.
//
// A community driver built against a newer NDK imports symbols versioned against a libc
// this device does not have, and the linker then refuses it. adrenotools reports that to
// logcat and quietly substitutes the system driver, so from the app's side the load
// "succeeded" and the user runs a driver they did not choose.
//
// The requirement is stated in the file: DT_VERNEED / .gnu.version_r lists the libc
// versions it needs, e.g. LIBC_36 for API 36. Comparing that against the running API
// turns "failed to load" into the actual reason, which is the difference between a
// usable bug report and a shrug. Mr Purple T29 needs LIBC_36 (Android 16) and its own
// meta.json claims minApi 30, so the metadata cannot be trusted for this -- only the
// binary can.
//
// Returns an empty string when nothing conclusive was found. Advisory only: the load is
// still attempted, so a wrong answer here costs a log line and never a working driver.
static std::string driver_libc_requirement_blocker(const std::string &soPath) {
  std::FILE *f = std::fopen(soPath.c_str(), "rb");
  if (f == nullptr) {
    return {};
  }

  std::vector<char> data;
  std::fseek(f, 0, SEEK_END);
  const long size = std::ftell(f);

  // Header tables live near the start and end; the symbol names they point at can be
  // anywhere, so read the whole file. These are ~15-20 MB and read once per driver
  // switch, not per boot.
  if (size <= 0 || size > (256 << 20)) {
    std::fclose(f);
    return {};
  }

  std::fseek(f, 0, SEEK_SET);
  data.resize(static_cast<size_t>(size));
  const size_t got = std::fread(data.data(), 1, data.size(), f);
  std::fclose(f);

  if (got != data.size() || data.size() < sizeof(Elf64_Ehdr)) {
    return {};
  }

  const auto *ehdr = reinterpret_cast<const Elf64_Ehdr *>(data.data());

  if (std::memcmp(ehdr->e_ident, ELFMAG, SELFMAG) != 0 ||
      ehdr->e_ident[EI_CLASS] != ELFCLASS64 || ehdr->e_shoff == 0 ||
      ehdr->e_shentsize != sizeof(Elf64_Shdr)) {
    return {};
  }

  // Section headers rather than PT_DYNAMIC: they carry file offsets directly, so no
  // vaddr-to-offset mapping is needed. Shared objects keep them; if they are gone, this
  // check simply declines to answer.
  const auto section_at = [&](size_t i) -> const Elf64_Shdr * {
    const size_t off = ehdr->e_shoff + i * sizeof(Elf64_Shdr);
    if (off + sizeof(Elf64_Shdr) > data.size()) {
      return nullptr;
    }
    return reinterpret_cast<const Elf64_Shdr *>(data.data() + off);
  };

  for (size_t i = 0; i < ehdr->e_shnum; i++) {
    const Elf64_Shdr *sh = section_at(i);

    if (sh == nullptr || sh->sh_type != SHT_GNU_verneed) {
      continue;
    }

    const Elf64_Shdr *strtab = section_at(sh->sh_link);

    if (strtab == nullptr || strtab->sh_offset >= data.size()) {
      return {};
    }

    const char *strings = data.data() + strtab->sh_offset;
    const size_t strings_max = data.size() - strtab->sh_offset;

    size_t offset = sh->sh_offset;
    int highest_libc = 0;

    for (size_t entry = 0; entry < sh->sh_info; entry++) {
      if (offset + sizeof(Elf64_Verneed) > data.size()) {
        break;
      }

      const auto *vn = reinterpret_cast<const Elf64_Verneed *>(data.data() + offset);
      size_t aux_offset = offset + vn->vn_aux;

      for (size_t aux = 0; aux < vn->vn_cnt; aux++) {
        if (aux_offset + sizeof(Elf64_Vernaux) > data.size()) {
          break;
        }

        const auto *vna = reinterpret_cast<const Elf64_Vernaux *>(data.data() + aux_offset);

        if (vna->vna_name < strings_max) {
          const char *name = strings + vna->vna_name;
          int level = 0;

          // Only LIBC_<n> is a device-capability statement. Anything else (LIBC,
          // LIBC_PRIVATE, other sonames) says nothing about the API level.
          if (std::sscanf(name, "LIBC_%d", &level) == 1 && level > highest_libc) {
            highest_libc = level;
          }
        }

        if (vna->vna_next == 0) {
          break;
        }

        aux_offset += vna->vna_next;
      }

      if (vn->vn_next == 0) {
        break;
      }

      offset += vn->vn_next;
    }

    const int device_api = android_get_device_api_level();

    if (highest_libc > 0 && device_api > 0 && highest_libc > device_api) {
      char buf[256];
      std::snprintf(buf, sizeof(buf),
                    "it requires LIBC_%d (Android API %d) but this device provides API %d",
                    highest_libc, highest_libc, device_api);
      return buf;
    }

    return {};
  }

  return {};
}
#endif // __aarch64__

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_setCustomDriver(JNIEnv *env, jobject, jstring jpath,
                                     jstring jlibraryName, jstring jhookDir) {
#ifdef __aarch64__
  if (rpcsxLib.setCustomDriver == nullptr) {
    return false;
  }

  auto path = unwrap(env, jpath);
  void *loader = nullptr;

  if (!path.empty()) {
      auto hookDir = unwrap(env, jhookDir);
      auto libraryName = unwrap(env, jlibraryName);
      __android_log_print(ANDROID_LOG_INFO, "RPCSX-UI", "Loading custom driver %s",
                          path.c_str());

      // Said before the attempt, because adrenotools swallows the failure: it logs to
      // logcat and hands back the system driver, so the caller cannot tell a real load
      // from a substitution, and the reason never reaches the emulator log at all.
      if (auto blocker = driver_libc_requirement_blocker(path + "/" + libraryName);
          !blocker.empty()) {
        const std::string report =
            "Custom driver '" + libraryName + "' cannot load on this device: " + blocker +
            ". It was built against a newer NDK than this Android version supports; the "
            "driver's own metadata does not carry this. The system driver will be used "
            "instead.";

        __android_log_print(ANDROID_LOG_ERROR, "RPCSX-UI", "%s", report.c_str());

        // Also to the emulator log, which is the file that gets attached to issues.
        // logcat alone means the reason exists and no report ever contains it.
        if (rpcsxLib.reportDriverProblem != nullptr) {
          rpcsxLib.reportDriverProblem(report);
        }
      }

      ::dlerror();
      loader = adrenotools_open_libvulkan(
              RTLD_NOW, ADRENOTOOLS_DRIVER_CUSTOM, nullptr, (hookDir + "/").c_str(),
              (path + "/").c_str(), libraryName.c_str(), nullptr, nullptr);

      if (loader == nullptr) {
          __android_log_print(ANDROID_LOG_INFO, "RPCSX-UI",
                              "Failed to load custom driver at '%s': %s",
                              path.c_str(), ::dlerror());
          return false;
      }
  }

  // Deliberately NOT dlclose()ing the previous handle.
  //
  // A Vulkan driver cannot be unloaded while anything resolved out of it is still reachable, and
  // from here there is no way to know that. VMA caches vkGetPhysicalDeviceMemoryProperties2 in the
  // allocator at creation time, so the address lives inside the driver library for as long as the
  // renderer does.
  //
  // Restart is the one flow where a start races a teardown that has not finished:
  // applyRendererPrefs() re-applies the driver on EVERY start, so it dlopen'd a new handle and
  // closed the old one while the previous VKGSRender was still unwinding. Its destructor then
  // freed its data heaps, VMA went to refresh its budget, and called through a pointer into a
  // library that was no longer mapped -- "Segfault executing location <addr> at <addr>", inside
  // VmaAllocator_T::UpdateVulkanBudget. The give-away was the fault address landing on the same
  // offset every time with a different base: a live function in an unmapped library, not a
  // corrupted pointer. That is the "Restart crashes the app" report, and the same for
  // apply-and-restart after picking a driver.
  //
  // Leaking one handle per driver SWITCH is the cheap side of this trade: it is bounded by how
  // many times a user changes driver in a session, the mapping is shared, and dlclose on an ICD
  // is not something the loader promises to honour anyway.
  rpcsxLib.setCustomDriver(loader);

  return true;
#else
  return false;
#endif // __aarch64__
}

// ---------------------------------------------------------------------------
// Save states
//
// Each is null-checked against the resolved core rather than assumed present,
// so an older core .so degrades to "returns false" instead of jumping through a
// null pointer.
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_saveState(JNIEnv *, jobject) {
  if (rpcsxLib.saveState == nullptr) {
    return false;
  }

  return rpcsxLib.saveState();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_loadState(JNIEnv *, jobject, jint index) {
  if (rpcsxLib.loadState == nullptr || index < 0) {
    return false;
  }

  return rpcsxLib.loadState(static_cast<unsigned int>(index));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_hasState(JNIEnv *, jobject, jint index) {
  if (rpcsxLib.hasState == nullptr || index < 0) {
    return false;
  }

  return rpcsxLib.hasState(static_cast<unsigned int>(index));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_saveStateToSlot(JNIEnv *, jobject, jint slot) {
  if (rpcsxLib.saveStateToSlot == nullptr || slot < 0) {
    return false;
  }

  return rpcsxLib.saveStateToSlot(static_cast<unsigned int>(slot));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_loadStateFromSlot(JNIEnv *, jobject, jint slot) {
  if (rpcsxLib.loadStateFromSlot == nullptr || slot < 0) {
    return false;
  }

  return rpcsxLib.loadStateFromSlot(static_cast<unsigned int>(slot));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_hasStateInSlot(JNIEnv *, jobject, jint slot) {
  if (rpcsxLib.hasStateInSlot == nullptr || slot < 0) {
    return false;
  }

  return rpcsxLib.hasStateInSlot(static_cast<unsigned int>(slot));
}

// ---------------------------------------------------------------------------
// Patches
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT jstring JNICALL
Java_net_rpcsx_RPCSX_patchEngineVersion(JNIEnv *env, jobject) {
  // Empty rather than a guessed version: the caller asks precisely because it
  // must not name a schema version of its own.
  if (rpcsxLib.patchEngineVersion == nullptr) {
    return wrap(env, "");
  }

  return wrap(env, rpcsxLib.patchEngineVersion());
}

extern "C" JNIEXPORT jint JNICALL
Java_net_rpcsx_RPCSX_patchesImport(JNIEnv *env, jobject, jstring jcontent) {
  if (rpcsxLib.patchesImport == nullptr) {
    return -1;
  }

  return rpcsxLib.patchesImport(unwrap(env, jcontent));
}

extern "C" JNIEXPORT jstring JNICALL
Java_net_rpcsx_RPCSX_probeDiscInfo(JNIEnv *env, jobject, jstring jpath,
                                   jstring jicon) {
  if (rpcsxLib.probeDiscInfo == nullptr) {
    return wrap(env, "{}");
  }
  return wrap(env, rpcsxLib.probeDiscInfo(unwrap(env, jpath), unwrap(env, jicon)));
}

extern "C" JNIEXPORT jstring JNICALL
Java_net_rpcsx_RPCSX_patchesList(JNIEnv *env, jobject, jstring jserial) {
  if (rpcsxLib.patchesList == nullptr) {
    return wrap(env, "[]");
  }

  return wrap(env, rpcsxLib.patchesList(unwrap(env, jserial)));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_patchSetEnabled(JNIEnv *env, jobject, jstring jhash,
                                     jstring jdescription, jstring jserial,
                                     jstring jappVersion, jboolean enabled) {
  if (rpcsxLib.patchSetEnabled == nullptr) {
    return false;
  }

  return rpcsxLib.patchSetEnabled(unwrap(env, jhash), unwrap(env, jdescription),
                                  unwrap(env, jserial),
                                  unwrap(env, jappVersion), enabled);
}

// ADPF telemetry. All three return 0 when unmeasured or on a core too old to export them,
// and the Kotlin side treats 0 as "skip this update" rather than feeding the OS a bogus hint.
extern "C" JNIEXPORT jlong JNICALL
Java_net_rpcsx_RPCSX_getFramePeriodNs(JNIEnv *, jobject) {
  if (rpcsxLib.getFramePeriodNs == nullptr) {
    return 0;
  }
  return static_cast<jlong>(rpcsxLib.getFramePeriodNs());
}

extern "C" JNIEXPORT jlong JNICALL
Java_net_rpcsx_RPCSX_getFrameWorkNs(JNIEnv *, jobject) {
  if (rpcsxLib.getFrameWorkNs == nullptr) {
    return 0;
  }
  return static_cast<jlong>(rpcsxLib.getFrameWorkNs());
}

extern "C" JNIEXPORT jint JNICALL
Java_net_rpcsx_RPCSX_getRsxThreadTid(JNIEnv *, jobject) {
  if (rpcsxLib.getRsxThreadTid == nullptr) {
    return 0;
  }
  return static_cast<jint>(rpcsxLib.getRsxThreadTid());
}

extern "C" JNIEXPORT jint JNICALL
Java_net_rpcsx_RPCSX_frameGenImportShaders(JNIEnv *env, jobject, jstring path) {
  if (!rpcsxLib.frameGenImportShaders) {
    return -1;
  }

  return rpcsxLib.frameGenImportShaders(unwrap(env, path));
}

extern "C" JNIEXPORT jint JNICALL
Java_net_rpcsx_RPCSX_frameGenShaderCount(JNIEnv *, jobject) {
  return rpcsxLib.frameGenShaderCount ? rpcsxLib.frameGenShaderCount() : 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_net_rpcsx_RPCSX_frameGenShaderError(JNIEnv *env, jobject) {
  const char *msg = rpcsxLib.frameGenShaderError ? rpcsxLib.frameGenShaderError() : "";
  return env->NewStringUTF(msg ? msg : "");
}
