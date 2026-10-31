#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <algorithm>
#include <android/api-level.h>
#include <android/dlext.h>
#include <android/hardware_buffer.h>
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
#include <sys/stat.h>
#include <unistd.h>
#include <utility>
#include <vector>

#if defined(__aarch64__)
#include <adrenotools/driver.h>
#include <adrenotools/priv.h>
#endif

struct CoreApi {
  bool (*overlayPadData)(int port, int digital1, int digital2, int leftStickX,
                         int leftStickY, int rightStickX, int rightStickY);
  bool (*overlayPadPressure)(int port, const int *values, int count);
  // Remote play capture (android/src/remote_play.cpp in the core).
  bool (*remotePlayStart)(int maxWidth, int maxHeight);
  void (*remotePlayStop)();
  int (*remotePlayPollVideo)(uint8_t *dst, int64_t cap, int32_t *info, int timeoutMs);
  int (*remotePlayPollAudio)(int16_t *dst, int capFrames, int timeoutMs);
  void (*remotePlayCounters)(uint32_t *out);
  void (*remotePlayReleaseFrame)(int slot, uint32_t seq);
  AHardwareBuffer *(*remotePlayHardwareBuffer)(int slot, uint32_t *generation);
  void (*remotePlaySetHardware)(bool enabled);
  bool (*remotePlayCanCapture)();
  bool (*remotePlayUpnpPrepare)(char *lanAddr, int lanAddrLen);
  int (*remotePlayUpnpMap)(int internalPort, int externalHint, int leaseSeconds, char *publicAddr, int publicAddrLen);
  void (*remotePlayUpnpUnmap)(int externalPort);
  bool (*keyboardKey)(int androidKeyCode, int unicode, bool pressed, bool repeat);
  bool (*initialize)(std::string_view rootDir, std::string_view user);
  void (*setSocInfo)(std::string_view socInfo);
  void (*logPreviousExit)(std::string_view line);
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
  void (*setAppBackgrounded)(bool);
  void (*openHomeMenu)();
  void (*captureFrame)();
  bool (*dumpFrame)(int mode);
  std::string (*getTitleId)();
  std::string (*getTitle)();
  unsigned long long (*getFramePeriodNs)();
  unsigned long long (*getFrameWorkNs)();
  int (*getRsxThreadTid)();
  std::string (*getCurrentTrophyName)();
  bool (*surfaceEvent)(JNIEnv *env, jobject surface, jint event);
  void (*surfaceSizeChanged)(int width, int height);
  void (*setPadSensor)(int port, int x, int y, int z, int g);
  int (*getPadRumble)(int port);
  void (*setThermals)(float cpu, float gpu, float battery, bool show);
  void (*setRenderPosition)(bool portraitTop, int topInset);
  void (*setPadDeviceClasses)(const int *classes, int count);
  bool (*usbDeviceEvent)(int fd, int vendorId, int productId, int event);
  bool (*installFw)(JNIEnv *env, int fd, long progressId);
  bool (*isInstallableFile)(jint fd);
  jstring (*getDirInstallPath)(JNIEnv *env, jint fd);
  jstring (*probePkgInfo)(JNIEnv *env, jint fd);
  bool (*isRestartPending)();
  bool (*install)(JNIEnv *env, int fd, long progressId);
  bool (*extractPkgTo)(JNIEnv *env, int fd, long progressId, const char *dest);
  void (*installStorageBridge)(JNIEnv *env);
  void (*installSoundBridge)(JNIEnv *env);
  bool (*installKey)(JNIEnv *env, int fd, long progressId,
                     std::string_view gamePath);
  std::string (*systemInfo)();
  void (*loginUser)(std::string_view userId);
  void (*logAndroid)(const char *message);
  std::string (*getUser)();
  std::string (*settingsGet)(std::string_view path);
  bool (*settingsSet)(std::string_view path, std::string_view valueString);
  int (*frameGenImportShaders)(std::string_view path);
  int (*frameGenShaderCount)();
  int (*getPlayCompileState)();
  const char *(*frameGenShaderError)();
  const char *(*rpcnGetConfig)();
  void (*rpcnSetConfig)(std::string_view host, std::string_view npid,
                        std::string_view password, std::string_view token);
  const char *(*rpcnAddFriend)(std::string_view npid);
  const char *(*rpcnRemoveFriend)(std::string_view npid);
  const char *(*rpcnGetFriends)();
  const char *(*rpcnFriendsSession)(bool open);
  const char *(*rpcnGetInvites)();
  const char *(*rpcnAcceptInvite)(uint64_t id);
  const char *(*rpcnDeclineInvite)(uint64_t id);
  const char *(*rpcnCreateAccount)(std::string_view npid, std::string_view password,
                                   std::string_view onlineName, std::string_view email);
  const char *(*rpcnResendToken)(std::string_view npid, std::string_view password);
  const char *(*rpcnSendResetToken)(std::string_view npid, std::string_view email);
  const char *(*rpcnResetPassword)(std::string_view npid, std::string_view token,
                                   std::string_view password);
  const char *(*rpcnTestLogin)();
  const char *(*rpcnSignIn)(std::string_view host, std::string_view npid,
                            std::string_view password, std::string_view token);
  const char *(*rpcnDeleteTrophies)();
  const char *(*rpcnAddHost)(std::string_view desc, std::string_view host);
  const char *(*rpcnDelHost)(std::string_view desc, std::string_view host);
  void (*rpcnResetHosts)();
  void (*rpcnSetIpv6)(bool enabled);
  const char *(*rpcnStatus)();
  bool (*rpcnSignedIn)();
  void (*setMicDevices)(std::string_view list);
  bool (*micTestStart)(std::string_view device);
  float (*micTestLevel)();
  void (*micTestStop)();
  int32_t (*skylanderLoad)(std::string_view path);
  bool (*skylanderRemove)(int32_t slot);
  int32_t (*skylanderLight)();
  bool (*infinityLoad)(std::string_view path, int32_t position);
  bool (*infinityRemove)(int32_t position);
  bool (*dimensionsLoad)(std::string_view path, int32_t index);
  bool (*dimensionsRemove)(int32_t index);
  bool (*skylanderCreate)(std::string_view path, int32_t id, int32_t variant);
  bool (*infinityCreate)(std::string_view path, int32_t figure, int32_t series);
  bool (*dimensionsCreate)(std::string_view path, int32_t id);
  const char *(*avatarList)();
  const char *(*avatarCurrent)();
  bool (*avatarImage)(std::string_view path, const uint8_t **data, size_t *size);
  bool (*avatarSelect)(std::string_view path);
  bool (*avatarUseImage)(const uint8_t *data, size_t size);
  void (*usbSetPassthrough)(std::string_view fds);
  int32_t (*usbPassedThrough)();
  const char *(*takeGameError)();
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
  bool (*isStateSlotReadable)(unsigned int slot);
  bool (*deleteStateFromSlot)(unsigned int slot);
  std::string (*patchEngineVersion)();
  int (*patchesImport)(std::string_view content);
  int (*patchesWrite)(std::string_view file, std::string_view content);
  std::string (*patchesList)(std::string_view serial);
  std::string (*probeDiscInfo)(std::string_view isoPath, std::string_view iconOut);
  int (*changeDisc)(std::string_view path);
  int (*discSwapState)();
  std::string (*getDiscPlaylist)();
  std::string (*gameContentId)(std::string_view gamePath);
  bool (*patchSetEnabled)(std::string_view hash, std::string_view description,
                          std::string_view serial, std::string_view appVersion,
                          bool enabled);
};

struct CoreLibrary : CoreApi {
  void *handle = nullptr;

  CoreLibrary() = default;
  CoreLibrary(const CoreLibrary &) = delete;
  CoreLibrary(CoreLibrary &&other) { swap(other); }
  CoreLibrary &operator=(CoreLibrary &&other) {
    swap(other);
    return *this;
  }
  ~CoreLibrary() {
    if (handle) {
      ::dlclose(handle);
    }
  }

  void swap(CoreLibrary &other) noexcept {
    std::swap(handle, other.handle);
    std::swap(static_cast<CoreApi &>(*this), static_cast<CoreApi &>(other));
  }

  static std::optional<CoreLibrary> Open(const char *path) {
    void *handle = ::dlopen(path, RTLD_LOCAL | RTLD_NOW);
    if (handle == nullptr) {
      __android_log_print(ANDROID_LOG_ERROR, "ARMSX3-UI",
                          "Failed to open the core library at %s, error %s", path,
                          ::dlerror());
      return {};
    }

    CoreLibrary result;
    result.handle = handle;

    // clang-format off
    result.overlayPadData = reinterpret_cast<decltype(overlayPadData)>(dlsym(handle, "_armsx3_overlayPadData"));
    result.overlayPadPressure = reinterpret_cast<decltype(overlayPadPressure)>(dlsym(handle, "_armsx3_overlayPadPressure"));
    result.remotePlayStart = reinterpret_cast<decltype(remotePlayStart)>(dlsym(handle, "_armsx3_remotePlayStart"));
    result.remotePlayStop = reinterpret_cast<decltype(remotePlayStop)>(dlsym(handle, "_armsx3_remotePlayStop"));
    result.remotePlayPollVideo = reinterpret_cast<decltype(remotePlayPollVideo)>(dlsym(handle, "_armsx3_remotePlayPollVideo"));
    result.remotePlayPollAudio = reinterpret_cast<decltype(remotePlayPollAudio)>(dlsym(handle, "_armsx3_remotePlayPollAudio"));
    result.remotePlayCounters = reinterpret_cast<decltype(remotePlayCounters)>(dlsym(handle, "_armsx3_remotePlayCounters"));
    result.remotePlayReleaseFrame = reinterpret_cast<decltype(remotePlayReleaseFrame)>(dlsym(handle, "_armsx3_remotePlayReleaseFrame"));
    result.remotePlayHardwareBuffer = reinterpret_cast<decltype(remotePlayHardwareBuffer)>(dlsym(handle, "_armsx3_remotePlayHardwareBuffer"));
    result.remotePlaySetHardware = reinterpret_cast<decltype(remotePlaySetHardware)>(dlsym(handle, "_armsx3_remotePlaySetHardware"));
    result.remotePlayCanCapture = reinterpret_cast<decltype(remotePlayCanCapture)>(dlsym(handle, "_armsx3_remotePlayCanCapture"));
    result.remotePlayUpnpPrepare = reinterpret_cast<decltype(remotePlayUpnpPrepare)>(dlsym(handle, "_armsx3_remotePlayUpnpPrepare"));
    result.remotePlayUpnpMap = reinterpret_cast<decltype(remotePlayUpnpMap)>(dlsym(handle, "_armsx3_remotePlayUpnpMap"));
    result.remotePlayUpnpUnmap = reinterpret_cast<decltype(remotePlayUpnpUnmap)>(dlsym(handle, "_armsx3_remotePlayUpnpUnmap"));
    result.keyboardKey = reinterpret_cast<decltype(keyboardKey)>(dlsym(handle, "_armsx3_keyboardKey"));
    result.initialize = reinterpret_cast<decltype(initialize)>(dlsym(handle, "_armsx3_initialize"));
    result.setSocInfo = reinterpret_cast<decltype(setSocInfo)>(dlsym(handle, "_armsx3_setSocInfo"));
    result.logPreviousExit = reinterpret_cast<decltype(logPreviousExit)>(dlsym(handle, "_armsx3_logPreviousExit"));
    result.processCompilationQueue = reinterpret_cast<decltype(processCompilationQueue)>(dlsym(handle, "_armsx3_processCompilationQueue"));
    result.startMainThreadProcessor = reinterpret_cast<decltype(startMainThreadProcessor)>(dlsym(handle, "_armsx3_startMainThreadProcessor"));
    result.collectGameInfo = reinterpret_cast<decltype(collectGameInfo)>(dlsym(handle, "_armsx3_collectGameInfo"));
    result.shutdown = reinterpret_cast<decltype(shutdown)>(dlsym(handle, "_armsx3_shutdown"));
    result.boot = reinterpret_cast<decltype(boot)>(dlsym(handle, "_armsx3_boot"));
    result.getState = reinterpret_cast<decltype(getState)>(dlsym(handle, "_armsx3_getState"));
    result.kill = reinterpret_cast<decltype(kill)>(dlsym(handle, "_armsx3_kill"));
    result.resume = reinterpret_cast<decltype(resume)>(dlsym(handle, "_armsx3_resume"));
    result.pause = reinterpret_cast<decltype(pause)>(dlsym(handle, "_armsx3_pause"));
    result.setAppBackgrounded = reinterpret_cast<decltype(setAppBackgrounded)>(dlsym(handle, "_armsx3_setAppBackgrounded"));
    result.captureFrame = reinterpret_cast<decltype(captureFrame)>(dlsym(handle, "_armsx3_captureFrame"));
    result.dumpFrame = reinterpret_cast<decltype(dumpFrame)>(dlsym(handle, "_armsx3_dumpFrame"));
    result.openHomeMenu = reinterpret_cast<decltype(openHomeMenu)>(dlsym(handle, "_armsx3_openHomeMenu"));
    result.getTitleId = reinterpret_cast<decltype(getTitleId)>(dlsym(handle, "_armsx3_getTitleId"));
    result.getTitle = reinterpret_cast<decltype(getTitle)>(dlsym(handle, "_armsx3_getTitle"));
    result.getFramePeriodNs = reinterpret_cast<decltype(getFramePeriodNs)>(dlsym(handle, "_armsx3_getFramePeriodNs"));
    result.getFrameWorkNs = reinterpret_cast<decltype(getFrameWorkNs)>(dlsym(handle, "_armsx3_getFrameWorkNs"));
    result.getRsxThreadTid = reinterpret_cast<decltype(getRsxThreadTid)>(dlsym(handle, "_armsx3_getRsxThreadTid"));
    result.getCurrentTrophyName = reinterpret_cast<decltype(getCurrentTrophyName)>(dlsym(handle, "_armsx3_getCurrentTrophyName"));
    result.surfaceEvent = reinterpret_cast<decltype(surfaceEvent)>(dlsym(handle, "_armsx3_surfaceEvent"));
    result.surfaceSizeChanged = reinterpret_cast<decltype(surfaceSizeChanged)>(dlsym(handle, "_armsx3_surfaceSizeChanged"));
    result.setPadSensor = reinterpret_cast<decltype(setPadSensor)>(dlsym(handle, "_armsx3_setPadSensor"));
    result.getPadRumble = reinterpret_cast<decltype(getPadRumble)>(dlsym(handle, "_armsx3_getPadRumble"));
    result.setThermals = reinterpret_cast<decltype(setThermals)>(dlsym(handle, "_armsx3_setThermals"));
    // Optional: a core built before this simply centres the image, as it always did.
    result.setRenderPosition = reinterpret_cast<decltype(setRenderPosition)>(dlsym(handle, "_armsx3_setRenderPosition"));
    // Optional: a core without it reports every port as a standard pad, as it always did.
    result.setPadDeviceClasses = reinterpret_cast<decltype(setPadDeviceClasses)>(dlsym(handle, "_armsx3_setPadDeviceClasses"));
    result.usbDeviceEvent = reinterpret_cast<decltype(usbDeviceEvent)>(dlsym(handle, "_armsx3_usbDeviceEvent"));
    result.installFw = reinterpret_cast<decltype(installFw)>(dlsym(handle, "_armsx3_installFw"));
    result.isInstallableFile = reinterpret_cast<decltype(isInstallableFile)>(dlsym(handle, "_armsx3_isInstallableFile"));
    result.getDirInstallPath = reinterpret_cast<decltype(getDirInstallPath)>(dlsym(handle, "_armsx3_getDirInstallPath"));
    // Optional: a core predating the library listing uninstalled packages has no such
    // symbol, and the scan then skips .pkg files exactly as it always did.
    result.probePkgInfo = reinterpret_cast<decltype(probePkgInfo)>(dlsym(handle, "_armsx3_probePkgInfo"));
    // Optional: a core without it simply never reports a pending restart, which is
    // the behaviour the run loop had before this existed.
    result.isRestartPending = reinterpret_cast<decltype(isRestartPending)>(dlsym(handle, "_armsx3_isRestartPending"));
    result.install = reinterpret_cast<decltype(install)>(dlsym(handle, "_armsx3_install"));
    result.extractPkgTo = reinterpret_cast<decltype(extractPkgTo)>(dlsym(handle, "_armsx3_extractPkgTo"));
    // Optional: a core built before the storage bridge simply has no such symbol, and
    // the Kotlin side then keeps resolving picked folders to filesystem paths.
    result.installStorageBridge = reinterpret_cast<decltype(installStorageBridge)>(dlsym(handle, "_armsx3_installStorageBridge"));
    // Optional: a core without it simply stays silent, as it always has.
    result.installSoundBridge = reinterpret_cast<decltype(installSoundBridge)>(dlsym(handle, "_armsx3_installSoundBridge"));
    result.installKey = reinterpret_cast<decltype(installKey)>(dlsym(handle, "_armsx3_installKey"));
    result.systemInfo = reinterpret_cast<decltype(systemInfo)>(dlsym(handle, "_armsx3_systemInfo"));
    result.loginUser = reinterpret_cast<decltype(loginUser)>(dlsym(handle, "_armsx3_loginUser"));
    result.logAndroid = reinterpret_cast<decltype(logAndroid)>(dlsym(handle, "_armsx3_logAndroid"));
    result.getUser = reinterpret_cast<decltype(getUser)>(dlsym(handle, "_armsx3_getUser"));
    result.settingsGet = reinterpret_cast<decltype(settingsGet)>(dlsym(handle, "_armsx3_settingsGet"));
    // Optional like the frame-gen group above: a core predating RPCN support simply has no
    // such symbols, and the Kotlin side treats a null as "this build cannot do RPCN".
    result.rpcnGetConfig = reinterpret_cast<decltype(rpcnGetConfig)>(dlsym(handle, "_armsx3_rpcnGetConfig"));
    result.rpcnSetConfig = reinterpret_cast<decltype(rpcnSetConfig)>(dlsym(handle, "_armsx3_rpcnSetConfig"));
    result.rpcnAddFriend = reinterpret_cast<decltype(rpcnAddFriend)>(dlsym(handle, "_armsx3_rpcnAddFriend"));
    result.rpcnRemoveFriend = reinterpret_cast<decltype(rpcnRemoveFriend)>(dlsym(handle, "_armsx3_rpcnRemoveFriend"));
    result.rpcnGetFriends = reinterpret_cast<decltype(rpcnGetFriends)>(dlsym(handle, "_armsx3_rpcnGetFriends"));
    result.rpcnFriendsSession = reinterpret_cast<decltype(rpcnFriendsSession)>(dlsym(handle, "_armsx3_rpcnFriendsSession"));
    result.rpcnGetInvites = reinterpret_cast<decltype(rpcnGetInvites)>(dlsym(handle, "_armsx3_rpcnGetInvites"));
    result.rpcnAcceptInvite = reinterpret_cast<decltype(rpcnAcceptInvite)>(dlsym(handle, "_armsx3_rpcnAcceptInvite"));
    result.rpcnDeclineInvite = reinterpret_cast<decltype(rpcnDeclineInvite)>(dlsym(handle, "_armsx3_rpcnDeclineInvite"));
    result.rpcnCreateAccount = reinterpret_cast<decltype(rpcnCreateAccount)>(dlsym(handle, "_armsx3_rpcnCreateAccount"));
    result.rpcnResendToken = reinterpret_cast<decltype(rpcnResendToken)>(dlsym(handle, "_armsx3_rpcnResendToken"));
    result.rpcnSendResetToken = reinterpret_cast<decltype(rpcnSendResetToken)>(dlsym(handle, "_armsx3_rpcnSendResetToken"));
    result.rpcnResetPassword = reinterpret_cast<decltype(rpcnResetPassword)>(dlsym(handle, "_armsx3_rpcnResetPassword"));
    result.rpcnTestLogin = reinterpret_cast<decltype(rpcnTestLogin)>(dlsym(handle, "_armsx3_rpcnTestLogin"));
    result.rpcnSignIn = reinterpret_cast<decltype(rpcnSignIn)>(dlsym(handle, "_armsx3_rpcnSignIn"));
    result.rpcnDeleteTrophies = reinterpret_cast<decltype(rpcnDeleteTrophies)>(dlsym(handle, "_armsx3_rpcnDeleteTrophies"));
    result.rpcnAddHost = reinterpret_cast<decltype(rpcnAddHost)>(dlsym(handle, "_armsx3_rpcnAddHost"));
    result.rpcnDelHost = reinterpret_cast<decltype(rpcnDelHost)>(dlsym(handle, "_armsx3_rpcnDelHost"));
    result.rpcnResetHosts = reinterpret_cast<decltype(rpcnResetHosts)>(dlsym(handle, "_armsx3_rpcnResetHosts"));
    result.rpcnSetIpv6 = reinterpret_cast<decltype(rpcnSetIpv6)>(dlsym(handle, "_armsx3_rpcnSetIpv6"));
    result.rpcnStatus = reinterpret_cast<decltype(rpcnStatus)>(dlsym(handle, "_armsx3_rpcnStatus"));
    result.rpcnSignedIn = reinterpret_cast<decltype(rpcnSignedIn)>(dlsym(handle, "_armsx3_rpcnSignedIn"));
    result.setMicDevices = reinterpret_cast<decltype(setMicDevices)>(dlsym(handle, "_armsx3_setMicDevices"));
    result.micTestStart = reinterpret_cast<decltype(micTestStart)>(dlsym(handle, "_armsx3_micTestStart"));
    result.micTestLevel = reinterpret_cast<decltype(micTestLevel)>(dlsym(handle, "_armsx3_micTestLevel"));
    result.micTestStop = reinterpret_cast<decltype(micTestStop)>(dlsym(handle, "_armsx3_micTestStop"));
    result.skylanderLoad = reinterpret_cast<decltype(skylanderLoad)>(dlsym(handle, "_armsx3_skylanderLoad"));
    result.skylanderRemove = reinterpret_cast<decltype(skylanderRemove)>(dlsym(handle, "_armsx3_skylanderRemove"));
    result.skylanderLight = reinterpret_cast<decltype(skylanderLight)>(dlsym(handle, "_armsx3_skylanderLight"));
    result.infinityLoad = reinterpret_cast<decltype(infinityLoad)>(dlsym(handle, "_armsx3_infinityLoad"));
    result.infinityRemove = reinterpret_cast<decltype(infinityRemove)>(dlsym(handle, "_armsx3_infinityRemove"));
    result.dimensionsLoad = reinterpret_cast<decltype(dimensionsLoad)>(dlsym(handle, "_armsx3_dimensionsLoad"));
    result.dimensionsRemove = reinterpret_cast<decltype(dimensionsRemove)>(dlsym(handle, "_armsx3_dimensionsRemove"));
    result.skylanderCreate = reinterpret_cast<decltype(skylanderCreate)>(dlsym(handle, "_armsx3_skylanderCreate"));
    result.infinityCreate = reinterpret_cast<decltype(infinityCreate)>(dlsym(handle, "_armsx3_infinityCreate"));
    result.dimensionsCreate = reinterpret_cast<decltype(dimensionsCreate)>(dlsym(handle, "_armsx3_dimensionsCreate"));
    result.avatarList = reinterpret_cast<decltype(avatarList)>(dlsym(handle, "_armsx3_avatarList"));
    result.avatarCurrent = reinterpret_cast<decltype(avatarCurrent)>(dlsym(handle, "_armsx3_avatarCurrent"));
    result.avatarImage = reinterpret_cast<decltype(avatarImage)>(dlsym(handle, "_armsx3_avatarImage"));
    result.avatarSelect = reinterpret_cast<decltype(avatarSelect)>(dlsym(handle, "_armsx3_avatarSelect"));
    result.avatarUseImage = reinterpret_cast<decltype(avatarUseImage)>(dlsym(handle, "_armsx3_avatarUseImage"));
    result.usbSetPassthrough = reinterpret_cast<decltype(usbSetPassthrough)>(dlsym(handle, "_armsx3_usbSetPassthrough"));
    result.usbPassedThrough = reinterpret_cast<decltype(usbPassedThrough)>(dlsym(handle, "_armsx3_usbPassedThrough"));
    result.takeGameError = reinterpret_cast<decltype(takeGameError)>(dlsym(handle, "_armsx3_takeGameError"));
    result.settingsSet = reinterpret_cast<decltype(settingsSet)>(dlsym(handle, "_armsx3_settingsSet"));
    // Resolved without ensure(): a core built before frame generation existed simply has no such
    // symbol, and refusing to load it over a missing optional feature would be worse than the
    // feature being absent. The Kotlin side treats a null here as "unsupported".
    result.frameGenImportShaders = reinterpret_cast<decltype(frameGenImportShaders)>(dlsym(handle, "_armsx3_frameGenImportShaders"));
    result.frameGenShaderCount = reinterpret_cast<decltype(frameGenShaderCount)>(dlsym(handle, "_armsx3_frameGenShaderCount"));
    result.getPlayCompileState = reinterpret_cast<decltype(getPlayCompileState)>(dlsym(handle, "_armsx3_getPlayCompileState"));
    result.frameGenShaderError = reinterpret_cast<decltype(frameGenShaderError)>(dlsym(handle, "_armsx3_frameGenShaderError"));
    result.settingsBeginBatch = reinterpret_cast<decltype(settingsBeginBatch)>(dlsym(handle, "_armsx3_settingsBeginBatch"));
    result.settingsEndBatch = reinterpret_cast<decltype(settingsEndBatch)>(dlsym(handle, "_armsx3_settingsEndBatch"));
    result.installSplitPkg = reinterpret_cast<decltype(installSplitPkg)>(dlsym(handle, "_armsx3_installSplitPkg"));
    result.uninstallGame = reinterpret_cast<decltype(uninstallGame)>(dlsym(handle, "_armsx3_uninstallGame"));
    result.getVersion = reinterpret_cast<decltype(getVersion)>(dlsym(handle, "_armsx3_getVersion"));
    result.setCustomDriver = reinterpret_cast<decltype(setCustomDriver)>(dlsym(handle, "_armsx3_setCustomDriver"));
    result.reportDriverProblem = reinterpret_cast<decltype(reportDriverProblem)>(dlsym(handle, "_armsx3_reportDriverProblem"));
    result.saveState = reinterpret_cast<decltype(saveState)>(dlsym(handle, "_armsx3_saveState"));
    result.loadState = reinterpret_cast<decltype(loadState)>(dlsym(handle, "_armsx3_loadState"));
    result.hasState = reinterpret_cast<decltype(hasState)>(dlsym(handle, "_armsx3_hasState"));
    result.saveStateToSlot = reinterpret_cast<decltype(saveStateToSlot)>(dlsym(handle, "_armsx3_saveStateToSlot"));
    result.loadStateFromSlot = reinterpret_cast<decltype(loadStateFromSlot)>(dlsym(handle, "_armsx3_loadStateFromSlot"));
    result.hasStateInSlot = reinterpret_cast<decltype(hasStateInSlot)>(dlsym(handle, "_armsx3_hasStateInSlot"));
    result.isStateSlotReadable = reinterpret_cast<decltype(isStateSlotReadable)>(dlsym(handle, "_armsx3_isStateSlotReadable"));
    result.deleteStateFromSlot = reinterpret_cast<decltype(deleteStateFromSlot)>(dlsym(handle, "_armsx3_deleteStateFromSlot"));
    result.patchEngineVersion = reinterpret_cast<decltype(patchEngineVersion)>(dlsym(handle, "_armsx3_patchEngineVersion"));
    result.patchesImport = reinterpret_cast<decltype(patchesImport)>(dlsym(handle, "_armsx3_patchesImport"));
    result.patchesWrite = reinterpret_cast<decltype(patchesWrite)>(dlsym(handle, "_armsx3_patchesWrite"));
    result.patchesList = reinterpret_cast<decltype(patchesList)>(dlsym(handle, "_armsx3_patchesList"));
    result.probeDiscInfo = reinterpret_cast<decltype(probeDiscInfo)>(dlsym(handle, "_armsx3_probeDiscInfo"));
    result.changeDisc = reinterpret_cast<decltype(changeDisc)>(dlsym(handle, "_armsx3_changeDisc"));
    result.discSwapState = reinterpret_cast<decltype(discSwapState)>(dlsym(handle, "_armsx3_discSwapState"));
    result.getDiscPlaylist = reinterpret_cast<decltype(getDiscPlaylist)>(dlsym(handle, "_armsx3_getDiscPlaylist"));
    result.gameContentId = reinterpret_cast<decltype(gameContentId)>(dlsym(handle, "_armsx3_gameContentId"));
    result.patchSetEnabled = reinterpret_cast<decltype(patchSetEnabled)>(dlsym(handle, "_armsx3_patchSetEnabled"));
    // clang-format on

    return result;
  }
};

static CoreLibrary coreLib;

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
Java_com_armsx3_core_ARMSX3_openLibrary(JNIEnv *env, jobject, jstring path) {
  if (auto library = CoreLibrary::Open(unwrap(env, path).c_str())) {
    coreLib = std::move(*library);
    return true;
  }

  return false;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_armsx3_core_ARMSX3_getLibraryVersion(JNIEnv *env, jobject, jstring path) {
  if (auto library = CoreLibrary::Open(unwrap(env, path).c_str())) {
    if (auto getVersion = library->getVersion) {
      return wrap(env, getVersion());
    }
  }

  return {};
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_armsx3_core_ARMSX3_overlayPadData(
    JNIEnv *, jobject, jint port, jint digital1, jint digital2, jint leftStickX,
    jint leftStickY, jint rightStickX, jint rightStickY) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (coreLib.overlayPadData == nullptr) {
      return false;
  }

  return coreLib.overlayPadData(port, digital1, digital2, leftStickX,
                                 leftStickY, rightStickX, rightStickY);
}

// Remote play. The streaming threads pull frames and audio from the core through these; the core
// never calls back into Java for them. Every one fails soft on a core without the exports.
extern "C" JNIEXPORT jboolean JNICALL Java_com_armsx3_core_ARMSX3_remotePlayStart(
    JNIEnv *, jobject, jint maxWidth, jint maxHeight) {
  if (coreLib.remotePlayStart == nullptr) {
    return false;
  }

  return coreLib.remotePlayStart(maxWidth, maxHeight);
}

extern "C" JNIEXPORT void JNICALL Java_com_armsx3_core_ARMSX3_remotePlayStop(JNIEnv *, jobject) {
  if (coreLib.remotePlayStop != nullptr) {
    coreLib.remotePlayStop();
  }
}

// buffer: a direct ByteBuffer for an NV12 frame (Y plane, then interleaved UV, rows packed).
// info (6 ints): width, height, slot, generation, seq, unused. Returns 2 with a hardware frame
// (nothing written to buffer), 1 with an NV12 frame, 0 on timeout, -1 when capture is off, -2 when
// the buffer is too small for the frame described in info.
extern "C" JNIEXPORT jint JNICALL Java_com_armsx3_core_ARMSX3_remotePlayPollVideo(
    JNIEnv *env, jobject, jobject buffer, jintArray info, jint timeoutMs) {
  if (coreLib.remotePlayPollVideo == nullptr || buffer == nullptr || info == nullptr ||
      env->GetArrayLength(info) < 6) {
    return -1;
  }

  auto *dst = static_cast<uint8_t *>(env->GetDirectBufferAddress(buffer));
  const jlong cap = env->GetDirectBufferCapacity(buffer);
  if (dst == nullptr || cap <= 0) {
    return -1;
  }

  int32_t out[6] = {};
  const int result = coreLib.remotePlayPollVideo(dst, cap, out, timeoutMs);
  env->SetIntArrayRegion(info, 0, 6, out);
  return result;
}

// Remote play's hardware frames: slots the GPU fills with RGBA as AHardwareBuffers, handed to the
// video encoder as GL textures so a frame never passes through the CPU. The two calls below that
// touch GL need a current EGL context on the calling thread.

extern "C" JNIEXPORT void JNICALL Java_com_armsx3_core_ARMSX3_remotePlayReleaseFrame(JNIEnv *, jobject, jint slot, jint seq) {
  if (coreLib.remotePlayReleaseFrame != nullptr) {
    coreLib.remotePlayReleaseFrame(slot, static_cast<uint32_t>(seq));
  }
}

// Remote play online hosting: this device's address on the router's network, or null when there
// is no router to open a port on. Blocks about two seconds the first time.
extern "C" JNIEXPORT jstring JNICALL Java_com_armsx3_core_ARMSX3_remotePlayUpnpPrepare(JNIEnv *env, jobject) {
  char lan[64] = {};
  if (coreLib.remotePlayUpnpPrepare == nullptr || !coreLib.remotePlayUpnpPrepare(lan, sizeof(lan)) || lan[0] == 0) {
    return nullptr;
  }
  return env->NewStringUTF(lan);
}

// "public address:router port" for UDP internalPort opened on the router, or null.
extern "C" JNIEXPORT jstring JNICALL Java_com_armsx3_core_ARMSX3_remotePlayUpnpMap(
    JNIEnv *env, jobject, jint internalPort, jint externalHint, jint leaseSeconds) {
  char address[64] = {};
  const int port = coreLib.remotePlayUpnpMap == nullptr
      ? 0
      : coreLib.remotePlayUpnpMap(internalPort, externalHint, leaseSeconds, address, sizeof(address));
  if (port <= 0 || address[0] == 0) {
    return nullptr;
  }
  const std::string result = std::string(address) + ":" + std::to_string(port);
  return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL Java_com_armsx3_core_ARMSX3_remotePlayUpnpUnmap(JNIEnv *, jobject, jint externalPort) {
  if (coreLib.remotePlayUpnpUnmap != nullptr) {
    coreLib.remotePlayUpnpUnmap(externalPort);
  }
}

extern "C" JNIEXPORT void JNICALL Java_com_armsx3_core_ARMSX3_remotePlaySetHardware(JNIEnv *, jobject, jboolean enabled) {
  if (coreLib.remotePlaySetHardware != nullptr) {
    coreLib.remotePlaySetHardware(enabled);
  }
}

// Whether the running game can be hosted: its renderer captures frames (Vulkan only). True on a
// core without the check, which hosted regardless.
extern "C" JNIEXPORT jboolean JNICALL Java_com_armsx3_core_ARMSX3_remotePlayCanCapture(JNIEnv *, jobject) {
  return coreLib.remotePlayCanCapture == nullptr || coreLib.remotePlayCanCapture();
}

namespace {
struct RemotePlayEgl {
  PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC getNativeClientBuffer = nullptr;
  PFNEGLCREATEIMAGEKHRPROC createImage = nullptr;
  PFNEGLDESTROYIMAGEKHRPROC destroyImage = nullptr;
  PFNGLEGLIMAGETARGETTEXTURE2DOESPROC imageTargetTexture = nullptr;

  bool load() {
    if (createImage == nullptr) {
      getNativeClientBuffer = reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(eglGetProcAddress("eglGetNativeClientBufferANDROID"));
      createImage = reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(eglGetProcAddress("eglCreateImageKHR"));
      destroyImage = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(eglGetProcAddress("eglDestroyImageKHR"));
      imageTargetTexture = reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(eglGetProcAddress("glEGLImageTargetTexture2DOES"));
    }
    return getNativeClientBuffer != nullptr && createImage != nullptr && destroyImage != nullptr &&
           imageTargetTexture != nullptr;
  }
};

RemotePlayEgl remotePlayEgl;
} // namespace

// Binds a hardware frame slot's buffer to texture (GL_TEXTURE_EXTERNAL_OES). Returns the EGLImage
// to destroy later with remotePlayDestroyImage, or 0 when the slot has no buffer or the import
// failed; the app then asks for NV12 frames instead.
extern "C" JNIEXPORT jlong JNICALL Java_com_armsx3_core_ARMSX3_remotePlayImportFrame(JNIEnv *, jobject, jint slot, jint texture) {
  if (coreLib.remotePlayHardwareBuffer == nullptr || !remotePlayEgl.load()) {
    return 0;
  }

  uint32_t generation = 0;
  AHardwareBuffer *buffer = coreLib.remotePlayHardwareBuffer(slot, &generation);
  if (buffer == nullptr) {
    return 0;
  }

  const EGLDisplay display = eglGetCurrentDisplay();
  const EGLClientBuffer client = remotePlayEgl.getNativeClientBuffer(buffer);
  const EGLint attributes[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
  EGLImageKHR image = EGL_NO_IMAGE_KHR;

  if (display != EGL_NO_DISPLAY && client != nullptr) {
    image = remotePlayEgl.createImage(display, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, client, attributes);
  }

  // The image holds the buffer from here on.
  AHardwareBuffer_release(buffer);

  if (image == EGL_NO_IMAGE_KHR) {
    return 0;
  }

  // Errors left over from earlier GL work on this context would read as a failed bind.
  for (int i = 0; i < 16 && glGetError() != GL_NO_ERROR; i++) {
  }

  glBindTexture(GL_TEXTURE_EXTERNAL_OES, static_cast<GLuint>(texture));
  remotePlayEgl.imageTargetTexture(GL_TEXTURE_EXTERNAL_OES, static_cast<GLeglImageOES>(image));
  const bool bound = glGetError() == GL_NO_ERROR;
  glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);

  if (!bound) {
    remotePlayEgl.destroyImage(display, image);
    return 0;
  }

  return reinterpret_cast<jlong>(image);
}

extern "C" JNIEXPORT void JNICALL Java_com_armsx3_core_ARMSX3_remotePlayDestroyImage(JNIEnv *, jobject, jlong image) {
  if (image != 0 && remotePlayEgl.load()) {
    remotePlayEgl.destroyImage(eglGetCurrentDisplay(), reinterpret_cast<EGLImageKHR>(image));
  }
}

// out (4 ints): running totals of game frames seen while capturing, frames not read back, frames
// handed to the app and frames replaced before the app took them. False when the core lacks it.
extern "C" JNIEXPORT jboolean JNICALL Java_com_armsx3_core_ARMSX3_remotePlayCounters(
    JNIEnv *env, jobject, jintArray out) {
  if (coreLib.remotePlayCounters == nullptr || out == nullptr || env->GetArrayLength(out) < 4) {
    return false;
  }

  uint32_t counters[4] = {};
  coreLib.remotePlayCounters(counters);
  jint values[4];
  for (int i = 0; i < 4; i++) {
    values[i] = static_cast<jint>(counters[i]);
  }
  env->SetIntArrayRegion(out, 0, 4, values);
  return true;
}

// buffer: a direct ByteBuffer receiving stereo s16 at 48 kHz. Returns frames written, 0 on
// timeout, -1 when capture is off.
extern "C" JNIEXPORT jint JNICALL Java_com_armsx3_core_ARMSX3_remotePlayPollAudio(
    JNIEnv *env, jobject, jobject buffer, jint capFrames, jint timeoutMs) {
  if (coreLib.remotePlayPollAudio == nullptr || buffer == nullptr) {
    return -1;
  }

  auto *dst = static_cast<int16_t *>(env->GetDirectBufferAddress(buffer));
  const jlong cap = env->GetDirectBufferCapacity(buffer);
  if (dst == nullptr || cap < 4) {
    return -1;
  }

  const int frames = static_cast<int>(std::min<jlong>(capFrames, cap / 4));
  return coreLib.remotePlayPollAudio(dst, frames, timeoutMs);
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_armsx3_core_ARMSX3_overlayPadPressure(
    JNIEnv *env, jobject, jint port, jintArray values) {
  // Absent on a core older than this export: the pad still works, every button
  // is just digital, which is the behaviour that shipped before it existed.
  if (coreLib.overlayPadPressure == nullptr || values == nullptr) {
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
  const bool ok = coreLib.overlayPadPressure(
      port, reinterpret_cast<const int *>(elems), static_cast<int>(count));

  env->ReleasePrimitiveArrayCritical(values, elems, JNI_ABORT);
  return ok;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_armsx3_core_ARMSX3_keyboardKey(
    JNIEnv *, jobject, jint androidKeyCode, jint unicode, jboolean pressed,
    jboolean repeat) {
  // Absent on a core older than this export. Returning false is right either
  // way: it means "nothing consumed this key", which is also what an emulator
  // with no keyboard attached reports.
  if (coreLib.keyboardKey == nullptr) {
    return false;
  }

  return coreLib.keyboardKey(androidKeyCode, unicode, pressed == JNI_TRUE,
                              repeat == JNI_TRUE);
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_armsx3_core_ARMSX3_initialize(
    JNIEnv *env, jobject, jstring rootDir, jstring user, jstring socInfo) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (coreLib.initialize == nullptr) {
      return false;
  }

  // Before initialize(), which is where the core assembles its startup log.
  // Null on cores older than this export; the SoC line then reads "unknown".
  if (coreLib.setSocInfo != nullptr) {
      coreLib.setSocInfo(unwrap(env, socInfo));
  }

  return coreLib.initialize(unwrap(env, rootDir), unwrap(env, user));
}

// After initialize(), once the core's log file is open. Null on cores older than this export.
extern "C" JNIEXPORT void JNICALL
Java_com_armsx3_core_ARMSX3_logPreviousExit(JNIEnv *env, jobject, jstring line) {
  if (coreLib.logPreviousExit != nullptr) {
    coreLib.logPreviousExit(unwrap(env, line));
  }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_armsx3_core_ARMSX3_processCompilationQueue(JNIEnv *env, jobject) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (coreLib.processCompilationQueue == nullptr) {
      return false;
  }

  return coreLib.processCompilationQueue(env);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_armsx3_core_ARMSX3_startMainThreadProcessor(JNIEnv *env, jobject) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (coreLib.startMainThreadProcessor == nullptr) {
      return false;
  }

  return coreLib.startMainThreadProcessor(env);
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_armsx3_core_ARMSX3_collectGameInfo(
    JNIEnv *env, jobject, jstring jrootDir, jlong progressId) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (coreLib.collectGameInfo == nullptr) {
      return false;
  }

  return coreLib.collectGameInfo(env, unwrap(env, jrootDir), progressId);
}

extern "C" JNIEXPORT void JNICALL Java_com_armsx3_core_ARMSX3_shutdown(JNIEnv *env,
                                                                jobject) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (coreLib.shutdown == nullptr) {
      return;
  }

  return coreLib.shutdown();
}

extern "C" JNIEXPORT jint JNICALL Java_com_armsx3_core_ARMSX3_boot(JNIEnv *env,
                                                            jobject,
                                                            jstring jpath) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (coreLib.boot == nullptr) {
      return 0;
  }

  return coreLib.boot(unwrap(env, jpath));
}

extern "C" JNIEXPORT jint JNICALL Java_com_armsx3_core_ARMSX3_getState(JNIEnv *env,
                                                                jobject) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (coreLib.getState == nullptr) {
      return 0;
  }

  return coreLib.getState();
}

extern "C" JNIEXPORT void JNICALL Java_com_armsx3_core_ARMSX3_kill(JNIEnv *env,
                                                            jobject) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (coreLib.kill == nullptr) {
      return;
  }

  return coreLib.kill();
}

extern "C" JNIEXPORT void JNICALL Java_com_armsx3_core_ARMSX3_resume(JNIEnv *env,
                                                              jobject) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (coreLib.resume == nullptr) {
      return;
  }

  return coreLib.resume();
}

extern "C" JNIEXPORT void JNICALL Java_com_armsx3_core_ARMSX3_pause(JNIEnv *env,
                                                             jobject) {
  // Same null guard as resume: the core is dlopen()ed separately and may not be
  // up yet. A missing symbol also means an older core, so an app built against
  // this cannot assume the export is there.
  if (coreLib.pause == nullptr) {
      return;
  }

  return coreLib.pause();
}

extern "C" JNIEXPORT void JNICALL Java_com_armsx3_core_ARMSX3_setAppBackgrounded(JNIEnv *env,
                                                                          jobject,
                                                                          jboolean backgrounded) {
  // Same null guard as pause: the core may not be loaded yet, and an older core lacks the export.
  if (coreLib.setAppBackgrounded == nullptr) {
      return;
  }

  coreLib.setAppBackgrounded(backgrounded == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL Java_com_armsx3_core_ARMSX3_openHomeMenu(JNIEnv *env,
                                                                    jobject) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (coreLib.openHomeMenu == nullptr) {
      return;
  }

  return coreLib.openHomeMenu();
}

extern "C" JNIEXPORT void JNICALL Java_com_armsx3_core_ARMSX3_captureFrame(JNIEnv *env,
                                                                    jobject) {
  if (coreLib.captureFrame == nullptr) {
      return;
  }

  return coreLib.captureFrame();
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_armsx3_core_ARMSX3_dumpFrame(JNIEnv *env,
                                                                    jobject, jint mode) {
  if (coreLib.dumpFrame == nullptr) {
      return false;
  }

  return coreLib.dumpFrame(mode);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_armsx3_core_ARMSX3_getTitleId(JNIEnv *env, jobject) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (coreLib.getTitleId == nullptr) {
      return nullptr;
  }

  return wrap(env, coreLib.getTitleId());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_armsx3_core_ARMSX3_getTitle(JNIEnv *env, jobject) {
  // Same as getTitleId: the core may not be loaded yet.
  if (coreLib.getTitle == nullptr) {
      return nullptr;
  }

  return wrap(env, coreLib.getTitle());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_armsx3_core_ARMSX3_getCurrentTrophyName(JNIEnv *env, jobject) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  //
  // Also null on an OLDER core that predates this export, since it is resolved
  // by dlsym: the frontend must treat null as "unknown", not as "no trophies".
  if (coreLib.getCurrentTrophyName == nullptr) {
      return nullptr;
  }

  return wrap(env, coreLib.getCurrentTrophyName());
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_armsx3_core_ARMSX3_surfaceEvent(
    JNIEnv *env, jobject, jobject surface, jint event) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (coreLib.surfaceEvent == nullptr) {
      return false;
  }

  return coreLib.surfaceEvent(env, surface, event);
}

extern "C" JNIEXPORT void JNICALL Java_com_armsx3_core_ARMSX3_setPadSensor(
    JNIEnv *, jobject, jint port, jint x, jint y, jint z, jint g) {
  if (coreLib.setPadSensor == nullptr) {
      return;
  }

  coreLib.setPadSensor(port, x, y, z, g);
}

extern "C" JNIEXPORT jint JNICALL Java_com_armsx3_core_ARMSX3_getPadRumble(
    JNIEnv *, jobject, jint port) {
  if (coreLib.getPadRumble == nullptr) {
      return 0;
  }

  return coreLib.getPadRumble(port);
}

// Device temperatures for the perf overlay. Discovery is the app's job -- Android exposes no
// supported API for SoC temperatures, so it reads the thermal sysfs, whose zone naming and units
// are vendor-specific -- and this only carries the result across.
extern "C" JNIEXPORT void JNICALL Java_com_armsx3_core_ARMSX3_setThermals(
    JNIEnv *, jobject, jfloat cpu, jfloat gpu, jfloat battery, jboolean show) {
  if (coreLib.setThermals == nullptr) {
    return;
  }

  coreLib.setThermals(cpu, gpu, battery, show == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL Java_com_armsx3_core_ARMSX3_setRenderPosition(
    JNIEnv *, jobject, jboolean portraitTop, jint topInset) {
  if (coreLib.setRenderPosition == nullptr) {
    return;
  }

  coreLib.setRenderPosition(portraitTop == JNI_TRUE, topInset);
}

extern "C" JNIEXPORT void JNICALL Java_com_armsx3_core_ARMSX3_setPadDeviceClasses(
    JNIEnv *env, jobject, jintArray classes) {
  if (coreLib.setPadDeviceClasses == nullptr || classes == nullptr) {
    return;
  }

  const jsize count = env->GetArrayLength(classes);

  if (count <= 0) {
    return;
  }

  std::vector<int> values(static_cast<size_t>(count));
  env->GetIntArrayRegion(classes, 0, count, values.data());
  coreLib.setPadDeviceClasses(values.data(), static_cast<int>(count));
}

extern "C" JNIEXPORT void JNICALL Java_com_armsx3_core_ARMSX3_surfaceSizeChanged(
    JNIEnv *, jobject, jint width, jint height) {
  if (coreLib.surfaceSizeChanged == nullptr) {
      return;
  }

  coreLib.surfaceSizeChanged(width, height);
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_armsx3_core_ARMSX3_usbDeviceEvent(
    JNIEnv *env, jobject, jint fd, jint vendorId, jint productId, jint event) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (coreLib.usbDeviceEvent == nullptr) {
      return false;
  }

  return coreLib.usbDeviceEvent(fd, vendorId, productId, event);
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_armsx3_core_ARMSX3_installFw(
    JNIEnv *env, jobject, jint fd, jlong progressId) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (coreLib.installFw == nullptr) {
      return false;
  }

  return coreLib.installFw(env, fd, progressId);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_armsx3_core_ARMSX3_isInstallableFile(JNIEnv *env, jobject, jint fd) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (coreLib.isInstallableFile == nullptr) {
      return false;
  }

  return coreLib.isInstallableFile(fd);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_armsx3_core_ARMSX3_getDirInstallPath(JNIEnv *env, jobject, jint fd) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (coreLib.getDirInstallPath == nullptr) {
      return nullptr;
  }

  return coreLib.getDirInstallPath(env, fd);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_armsx3_core_ARMSX3_install(JNIEnv *env, jobject, jint fd, jlong progressId) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (coreLib.install == nullptr) {
      return false;
  }

  return coreLib.install(env, fd, progressId);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_armsx3_core_ARMSX3_isRestartPending(JNIEnv *, jobject) {
  return coreLib.isRestartPending != nullptr && coreLib.isRestartPending();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_armsx3_core_ARMSX3_probePkgInfo(JNIEnv *env, jobject, jint fd) {
  if (coreLib.probePkgInfo == nullptr) {
    return nullptr;
  }

  return coreLib.probePkgInfo(env, fd);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_armsx3_core_ARMSX3_installSoundBridge(JNIEnv *env, jobject) {
  if (coreLib.installSoundBridge == nullptr) {
    return false;
  }

  coreLib.installSoundBridge(env);
  return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_armsx3_core_ARMSX3_installStorageBridge(JNIEnv *env, jobject) {
  if (coreLib.installStorageBridge == nullptr) {
    return false;
  }

  coreLib.installStorageBridge(env);
  return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_armsx3_core_ARMSX3_extractPkgTo(JNIEnv *env, jobject, jint fd, jlong progressId,
                                  jstring dest) {
  if (coreLib.extractPkgTo == nullptr || dest == nullptr) {
    return false;
  }

  const char *destChars = env->GetStringUTFChars(dest, nullptr);
  if (destChars == nullptr) {
    return false;
  }

  const bool ok = coreLib.extractPkgTo(env, fd, progressId, destChars);
  env->ReleaseStringUTFChars(dest, destChars);
  return ok;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_armsx3_core_ARMSX3_installKey(
    JNIEnv *env, jobject, jint fd, jlong progressId, jstring gamePath) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (coreLib.installKey == nullptr) {
      return false;
  }

  return coreLib.installKey(env, fd, progressId, unwrap(env, gamePath));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_armsx3_core_ARMSX3_systemInfo(JNIEnv *env, jobject) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (coreLib.systemInfo == nullptr) {
      return nullptr;
  }

  return wrap(env, coreLib.systemInfo());
}

extern "C" JNIEXPORT void JNICALL
Java_com_armsx3_core_ARMSX3_logAndroid(JNIEnv *env, jobject, jstring message) {
  // Silent when the core is not open yet, like every other entry point here. A diagnostic that
  // crashed the app for being early would be worse than one that is missing.
  if (coreLib.logAndroid == nullptr || message == nullptr) {
    return;
  }

  coreLib.logAndroid(unwrap(env, message).c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_armsx3_core_ARMSX3_loginUser(JNIEnv *env, jobject, jstring user_id) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (coreLib.loginUser == nullptr) {
      return;
  }

  return coreLib.loginUser(unwrap(env, user_id));
}

extern "C" JNIEXPORT jstring JNICALL Java_com_armsx3_core_ARMSX3_getUser(JNIEnv *env,
                                                                  jobject) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (coreLib.getUser == nullptr) {
      return nullptr;
  }

  return wrap(env, coreLib.getUser());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_armsx3_core_ARMSX3_settingsGet(JNIEnv *env, jobject, jstring jpath) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (coreLib.settingsGet == nullptr) {
      return nullptr;
  }

  return wrap(env, coreLib.settingsGet(unwrap(env, jpath)));
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_armsx3_core_ARMSX3_settingsSet(
    JNIEnv *env, jobject, jstring jpath, jstring jvalue) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (coreLib.settingsSet == nullptr) {
      return false;
  }

  return coreLib.settingsSet(unwrap(env, jpath), unwrap(env, jvalue));
}

// Defer the config file write until endBatch. Null-safe like the rest: an older core
// .so simply has no such symbol, and every settingsSet then saves as it always did.
extern "C" JNIEXPORT void JNICALL
Java_com_armsx3_core_ARMSX3_settingsBeginBatch(JNIEnv *, jobject) {
  if (coreLib.settingsBeginBatch != nullptr) {
    coreLib.settingsBeginBatch();
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_armsx3_core_ARMSX3_settingsEndBatch(JNIEnv *, jobject) {
  if (coreLib.settingsEndBatch != nullptr) {
    coreLib.settingsEndBatch();
  }
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_armsx3_core_ARMSX3_installSplitPkg(
    JNIEnv *env, jobject, jintArray jfds, jlong progressId) {
  if (coreLib.installSplitPkg == nullptr || jfds == nullptr) {
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

  return coreLib.installSplitPkg(env, fds.data(), static_cast<int>(count),
                                  static_cast<long>(progressId));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_armsx3_core_ARMSX3_uninstallGame(JNIEnv *env, jobject, jstring jpath) {
  if (coreLib.uninstallGame == nullptr) {
    return false;
  }

  return coreLib.uninstallGame(unwrap(env, jpath));
}

// Whether a user-supplied Vulkan driver has a GPU kernel interface to drive here. adrenotools
// loads any vendor's driver library; what decides it is the kernel side: Adreno's kgsl for Turnip,
// or Arm's kbase (/dev/mali0) for Mali, which the community PanVK builds for Android talk to
// directly. Only the driver screen's warning reads this.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_armsx3_core_ARMSX3_supportsCustomDriverLoading(JNIEnv *env,
                                                 jobject instance) {
  return access("/dev/kgsl-3d0", F_OK) == 0 || access("/dev/mali0", F_OK) == 0;
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
// is loaded. Ported from an earlier Android fork, which ships it default-off; kept default-off here
// for the same reason.
extern "C" JNIEXPORT void JNICALL
Java_com_armsx3_core_ARMSX3_setGpuTurbo(JNIEnv *, jobject, jboolean on) {
#if defined(__aarch64__)
  adrenotools_set_turbo(on == JNI_TRUE);
#else
  (void) on;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_armsx3_core_ARMSX3_getVersion(JNIEnv *env, jobject) {
  // The core is dlopen()ed separately and may not be up yet -- during
  // onboarding, or if it failed to load. Calling through a null pointer
  // is an instant SIGSEGV, so fail the call instead.
  if (coreLib.getVersion == nullptr) {
      return nullptr;
  }

  return wrap(env, coreLib.getVersion());
}

#if defined(__aarch64__)
// Whether the driver adrenotools was asked for is the driver it opened.
//
// adrenotools opens the driver only when the Vulkan loader first needs one, and when that
// fails (a library this device cannot link, a pack built against another kernel driver) it
// logs to logcat and opens the system driver instead. The load looked successful, the emulator
// logged "Vulkan driver bound (custom)", and Arm's own driver ran: a Mali-G720 user's PanVK
// pack "worked", with a log identical in every Vulkan line to a run on the system driver.
//
// So the loader is made to open its driver now, through the cheapest call that needs one, and
// the driver file is looked for among the files mapped into this process. By inode and name,
// since the same file can be reached as /data/data/<pkg> or /data/user/0/<pkg>. When the maps
// cannot be read the answer is yes, which is what every load was taken for before.
static bool custom_driver_loaded(void *loader, const std::string &soPath) {
  struct stat file {};
  if (::stat(soPath.c_str(), &file) != 0) {
    return false;
  }

  using enumerate_fn = int32_t (*)(const char *, uint32_t *, void *);
  if (auto enumerate = reinterpret_cast<enumerate_fn>(
          dlsym(loader, "vkEnumerateInstanceExtensionProperties"))) {
    uint32_t count = 0;
    enumerate(nullptr, &count, nullptr);
  }

  std::FILE *maps = std::fopen("/proc/self/maps", "re");
  if (maps == nullptr) {
    return true;
  }

  const std::string name = soPath.substr(soPath.find_last_of('/') + 1);
  bool found = false;
  char line[1024];

  while (!found && std::fgets(line, sizeof(line), maps) != nullptr) {
    // address perms offset dev inode path
    unsigned long inode = 0;
    int path_at = 0;
    if (std::sscanf(line, "%*s %*s %*s %*s %lu %n", &inode, &path_at) != 1 ||
        inode != file.st_ino) {
      continue;
    }

    std::string_view path{line + path_at};
    while (!path.empty() && (path.back() == '\n' || path.back() == ' ')) {
      path.remove_suffix(1);
    }

    found = path.size() > name.size() && path.substr(path.size() - name.size()) == name &&
            path[path.size() - name.size() - 1] == '/';
  }

  std::fclose(maps);
  return found;
}

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
Java_com_armsx3_core_ARMSX3_setCustomDriver(JNIEnv *env, jobject, jstring jpath,
                                     jstring jlibraryName, jstring jhookDir) {
#ifdef __aarch64__
  if (coreLib.setCustomDriver == nullptr) {
    return false;
  }

  auto path = unwrap(env, jpath);
  void *loader = nullptr;

  if (!path.empty()) {
      auto hookDir = unwrap(env, jhookDir);
      auto libraryName = unwrap(env, jlibraryName);
      __android_log_print(ANDROID_LOG_INFO, "ARMSX3-UI", "Loading custom driver %s",
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

        __android_log_print(ANDROID_LOG_ERROR, "ARMSX3-UI", "%s", report.c_str());

        // Also to the emulator log, which is the file that gets attached to issues.
        // logcat alone means the reason exists and no report ever contains it.
        if (coreLib.reportDriverProblem != nullptr) {
          coreLib.reportDriverProblem(report);
        }
      }

      ::dlerror();
      loader = adrenotools_open_libvulkan(
              RTLD_NOW, ADRENOTOOLS_DRIVER_CUSTOM, nullptr, (hookDir + "/").c_str(),
              (path + "/").c_str(), libraryName.c_str(), nullptr, nullptr);

      if (loader == nullptr || !custom_driver_loaded(loader, path + "/" + libraryName)) {
          const char *error = ::dlerror();
          const std::string report =
              "Custom driver '" + libraryName + "' could not be loaded on this device, so the "
              "system driver is used instead. The reason is in logcat, under hook_impl" +
              (error != nullptr ? std::string(": ") + error : std::string("."));

          __android_log_print(ANDROID_LOG_ERROR, "ARMSX3-UI", "%s", report.c_str());

          if (coreLib.reportDriverProblem != nullptr) {
            coreLib.reportDriverProblem(report);
          }

          // The system driver, bound as itself rather than left behind whatever adrenotools
          // fell back to, so every later log line names the driver that actually runs. Also
          // replaces a custom driver an earlier game left bound. false tells the caller the
          // driver it asked for is not the one in use.
          coreLib.setCustomDriver(nullptr);
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
  coreLib.setCustomDriver(loader);

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
Java_com_armsx3_core_ARMSX3_saveState(JNIEnv *, jobject) {
  if (coreLib.saveState == nullptr) {
    return false;
  }

  return coreLib.saveState();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_armsx3_core_ARMSX3_loadState(JNIEnv *, jobject, jint index) {
  if (coreLib.loadState == nullptr || index < 0) {
    return false;
  }

  return coreLib.loadState(static_cast<unsigned int>(index));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_armsx3_core_ARMSX3_hasState(JNIEnv *, jobject, jint index) {
  if (coreLib.hasState == nullptr || index < 0) {
    return false;
  }

  return coreLib.hasState(static_cast<unsigned int>(index));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_armsx3_core_ARMSX3_saveStateToSlot(JNIEnv *, jobject, jint slot) {
  if (coreLib.saveStateToSlot == nullptr || slot < 0) {
    return false;
  }

  return coreLib.saveStateToSlot(static_cast<unsigned int>(slot));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_armsx3_core_ARMSX3_loadStateFromSlot(JNIEnv *, jobject, jint slot) {
  if (coreLib.loadStateFromSlot == nullptr || slot < 0) {
    return false;
  }

  return coreLib.loadStateFromSlot(static_cast<unsigned int>(slot));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_armsx3_core_ARMSX3_hasStateInSlot(JNIEnv *, jobject, jint slot) {
  if (coreLib.hasStateInSlot == nullptr || slot < 0) {
    return false;
  }

  return coreLib.hasStateInSlot(static_cast<unsigned int>(slot));
}

// Readable unless the core says otherwise: a core too old to answer must not make every
// slot look like it holds a state from another version.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_armsx3_core_ARMSX3_isStateSlotReadable(JNIEnv *, jobject, jint slot) {
  if (coreLib.isStateSlotReadable == nullptr || slot < 0) {
    return true;
  }

  return coreLib.isStateSlotReadable(static_cast<unsigned int>(slot));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_armsx3_core_ARMSX3_deleteStateFromSlot(JNIEnv *, jobject, jint slot) {
  if (coreLib.deleteStateFromSlot == nullptr || slot < 0) {
    return false;
  }

  return coreLib.deleteStateFromSlot(static_cast<unsigned int>(slot));
}

// ---------------------------------------------------------------------------
// Patches
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT jstring JNICALL
Java_com_armsx3_core_ARMSX3_patchEngineVersion(JNIEnv *env, jobject) {
  // Empty rather than a guessed version: the caller asks precisely because it
  // must not name a schema version of its own.
  if (coreLib.patchEngineVersion == nullptr) {
    return wrap(env, "");
  }

  return wrap(env, coreLib.patchEngineVersion());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_armsx3_core_ARMSX3_patchesImport(JNIEnv *env, jobject, jstring jcontent) {
  if (coreLib.patchesImport == nullptr) {
    return -1;
  }

  return coreLib.patchesImport(unwrap(env, jcontent));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_armsx3_core_ARMSX3_patchesWrite(JNIEnv *env, jobject, jstring jfile,
                                  jstring jcontent) {
  if (coreLib.patchesWrite == nullptr) {
    return -1;
  }

  return coreLib.patchesWrite(unwrap(env, jfile), unwrap(env, jcontent));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_armsx3_core_ARMSX3_probeDiscInfo(JNIEnv *env, jobject, jstring jpath,
                                   jstring jicon) {
  if (coreLib.probeDiscInfo == nullptr) {
    return wrap(env, "{}");
  }
  return wrap(env, coreLib.probeDiscInfo(unwrap(env, jpath), unwrap(env, jicon)));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_armsx3_core_ARMSX3_changeDisc(JNIEnv *env, jobject, jstring jpath) {
  if (coreLib.changeDisc == nullptr) {
    return 1;
  }
  return coreLib.changeDisc(unwrap(env, jpath));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_armsx3_core_ARMSX3_discSwapState(JNIEnv *, jobject) {
  if (coreLib.discSwapState == nullptr) {
    return 0;
  }
  return coreLib.discSwapState();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_armsx3_core_ARMSX3_gameContentId(JNIEnv *env, jobject, jstring jpath) {
  if (coreLib.gameContentId == nullptr) {
    return wrap(env, "");
  }
  return wrap(env, coreLib.gameContentId(unwrap(env, jpath)));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_armsx3_core_ARMSX3_getDiscPlaylist(JNIEnv *env, jobject) {
  if (coreLib.getDiscPlaylist == nullptr) {
    return wrap(env, "{}");
  }
  return wrap(env, coreLib.getDiscPlaylist());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_armsx3_core_ARMSX3_patchesList(JNIEnv *env, jobject, jstring jserial) {
  if (coreLib.patchesList == nullptr) {
    return wrap(env, "[]");
  }

  return wrap(env, coreLib.patchesList(unwrap(env, jserial)));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_armsx3_core_ARMSX3_patchSetEnabled(JNIEnv *env, jobject, jstring jhash,
                                     jstring jdescription, jstring jserial,
                                     jstring jappVersion, jboolean enabled) {
  if (coreLib.patchSetEnabled == nullptr) {
    return false;
  }

  return coreLib.patchSetEnabled(unwrap(env, jhash), unwrap(env, jdescription),
                                  unwrap(env, jserial),
                                  unwrap(env, jappVersion), enabled);
}

// ADPF telemetry. All three return 0 when unmeasured or on a core too old to export them,
// and the Kotlin side treats 0 as "skip this update" rather than feeding the OS a bogus hint.
extern "C" JNIEXPORT jlong JNICALL
Java_com_armsx3_core_ARMSX3_getFramePeriodNs(JNIEnv *, jobject) {
  if (coreLib.getFramePeriodNs == nullptr) {
    return 0;
  }
  return static_cast<jlong>(coreLib.getFramePeriodNs());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_armsx3_core_ARMSX3_getFrameWorkNs(JNIEnv *, jobject) {
  if (coreLib.getFrameWorkNs == nullptr) {
    return 0;
  }
  return static_cast<jlong>(coreLib.getFrameWorkNs());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_armsx3_core_ARMSX3_getRsxThreadTid(JNIEnv *, jobject) {
  if (coreLib.getRsxThreadTid == nullptr) {
    return 0;
  }
  return static_cast<jint>(coreLib.getRsxThreadTid());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_armsx3_core_ARMSX3_frameGenImportShaders(JNIEnv *env, jobject, jstring path) {
  if (!coreLib.frameGenImportShaders) {
    return -1;
  }

  return coreLib.frameGenImportShaders(unwrap(env, path));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_armsx3_core_ARMSX3_frameGenShaderCount(JNIEnv *, jobject) {
  return coreLib.frameGenShaderCount ? coreLib.frameGenShaderCount() : 0;
}

// 0 while nothing compiles during play, else 1 + the PPU modules compiled so far in this stretch.
// A core without the symbol reports nothing, like the frame generation calls above.
extern "C" JNIEXPORT jint JNICALL
Java_com_armsx3_core_ARMSX3_getPlayCompileState(JNIEnv *, jobject) {
  return coreLib.getPlayCompileState ? coreLib.getPlayCompileState() : 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_armsx3_core_ARMSX3_frameGenShaderError(JNIEnv *env, jobject) {
  const char *msg = coreLib.frameGenShaderError ? coreLib.frameGenShaderError() : "";
  return env->NewStringUTF(msg ? msg : "");
}

// ---- RPCN ----
//
// Every one of these blocks on the network; the Kotlin side calls them off the UI thread.
// A null pointer means the core predates RPCN support, which is reported as a message
// rather than a crash so an old core degrades to "unavailable" instead of taking the app
// down.

static jstring rpcn_unavailable(JNIEnv *env) {
  return env->NewStringUTF("This build of the emulator core has no RPCN support.");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_armsx3_core_ARMSX3_rpcnGetConfig(JNIEnv *env, jobject) {
  if (!coreLib.rpcnGetConfig) return env->NewStringUTF("");
  const char *json = coreLib.rpcnGetConfig();
  return env->NewStringUTF(json ? json : "");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_armsx3_core_ARMSX3_rpcnStatus(JNIEnv *env, jobject) {
  if (!coreLib.rpcnStatus) return env->NewStringUTF("");
  const char *json = coreLib.rpcnStatus();
  return env->NewStringUTF(json ? json : "");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_armsx3_core_ARMSX3_rpcnSignedIn(JNIEnv *, jobject) {
  if (!coreLib.rpcnSignedIn) return JNI_FALSE;
  return coreLib.rpcnSignedIn() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_armsx3_core_ARMSX3_setMicDevices(JNIEnv *env, jobject, jstring list) {
  if (!coreLib.setMicDevices) return;

  const char *c = list ? env->GetStringUTFChars(list, nullptr) : nullptr;
  const std::string value = c ? c : "";
  if (c) env->ReleaseStringUTFChars(list, c);

  coreLib.setMicDevices(value);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_armsx3_core_ARMSX3_micTestStart(JNIEnv *env, jobject, jstring device) {
  if (!coreLib.micTestStart) return JNI_FALSE;

  const char *c = device ? env->GetStringUTFChars(device, nullptr) : nullptr;
  const std::string value = c ? c : "";
  if (c) env->ReleaseStringUTFChars(device, c);

  return coreLib.micTestStart(value) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_armsx3_core_ARMSX3_micTestLevel(JNIEnv *, jobject) {
  return coreLib.micTestLevel ? coreLib.micTestLevel() : -1.0f;
}

extern "C" JNIEXPORT void JNICALL
Java_com_armsx3_core_ARMSX3_micTestStop(JNIEnv *, jobject) {
  if (coreLib.micTestStop) coreLib.micTestStop();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_armsx3_core_ARMSX3_skylanderLoad(JNIEnv *env, jobject, jstring path) {
  if (!coreLib.skylanderLoad) return -1;

  const char *c = path ? env->GetStringUTFChars(path, nullptr) : nullptr;
  const std::string value = c ? c : "";
  if (c) env->ReleaseStringUTFChars(path, c);

  return coreLib.skylanderLoad(value);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_armsx3_core_ARMSX3_skylanderRemove(JNIEnv *, jobject, jint slot) {
  if (!coreLib.skylanderRemove) return JNI_FALSE;
  return coreLib.skylanderRemove(slot) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_armsx3_core_ARMSX3_skylanderLight(JNIEnv *, jobject) {
  return coreLib.skylanderLight ? coreLib.skylanderLight() : 0;
}

static std::string jstring_to_string(JNIEnv *env, jstring value) {
  const char *c = value ? env->GetStringUTFChars(value, nullptr) : nullptr;
  std::string result = c ? c : "";
  if (c) env->ReleaseStringUTFChars(value, c);
  return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_armsx3_core_ARMSX3_infinityLoad(JNIEnv *env, jobject, jstring path, jint position) {
  if (!coreLib.infinityLoad) return JNI_FALSE;
  return coreLib.infinityLoad(jstring_to_string(env, path), position) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_armsx3_core_ARMSX3_infinityRemove(JNIEnv *, jobject, jint position) {
  if (!coreLib.infinityRemove) return JNI_FALSE;
  return coreLib.infinityRemove(position) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_armsx3_core_ARMSX3_dimensionsLoad(JNIEnv *env, jobject, jstring path, jint index) {
  if (!coreLib.dimensionsLoad) return JNI_FALSE;
  return coreLib.dimensionsLoad(jstring_to_string(env, path), index) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_armsx3_core_ARMSX3_dimensionsRemove(JNIEnv *, jobject, jint index) {
  if (!coreLib.dimensionsRemove) return JNI_FALSE;
  return coreLib.dimensionsRemove(index) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_armsx3_core_ARMSX3_skylanderCreate(JNIEnv *env, jobject, jstring path, jint id, jint variant) {
  if (!coreLib.skylanderCreate) return JNI_FALSE;
  return coreLib.skylanderCreate(jstring_to_string(env, path), id, variant) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_armsx3_core_ARMSX3_infinityCreate(JNIEnv *env, jobject, jstring path, jint figure, jint series) {
  if (!coreLib.infinityCreate) return JNI_FALSE;
  return coreLib.infinityCreate(jstring_to_string(env, path), figure, series) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_armsx3_core_ARMSX3_dimensionsCreate(JNIEnv *env, jobject, jstring path, jint id) {
  if (!coreLib.dimensionsCreate) return JNI_FALSE;
  return coreLib.dimensionsCreate(jstring_to_string(env, path), id) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_armsx3_core_ARMSX3_avatarList(JNIEnv *env, jobject) {
  if (!coreLib.avatarList) return env->NewStringUTF("");
  const char *json = coreLib.avatarList();
  return env->NewStringUTF(json ? json : "");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_armsx3_core_ARMSX3_avatarCurrent(JNIEnv *env, jobject) {
  if (!coreLib.avatarCurrent) return env->NewStringUTF("");
  const char *path = coreLib.avatarCurrent();
  return env->NewStringUTF(path ? path : "");
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_armsx3_core_ARMSX3_avatarImage(JNIEnv *env, jobject, jstring path) {
  const uint8_t *data = nullptr;
  size_t size = 0;
  if (!coreLib.avatarImage || !coreLib.avatarImage(jstring_to_string(env, path), &data, &size)) return nullptr;
  jbyteArray out = env->NewByteArray(static_cast<jsize>(size));
  if (out) env->SetByteArrayRegion(out, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte *>(data));
  return out;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_armsx3_core_ARMSX3_avatarSelect(JNIEnv *env, jobject, jstring path) {
  if (!coreLib.avatarSelect) return JNI_FALSE;
  return coreLib.avatarSelect(jstring_to_string(env, path)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_armsx3_core_ARMSX3_avatarUseImage(JNIEnv *env, jobject, jbyteArray png) {
  if (!coreLib.avatarUseImage || !png) return JNI_FALSE;
  std::vector<uint8_t> bytes(static_cast<size_t>(env->GetArrayLength(png)));
  env->GetByteArrayRegion(png, 0, static_cast<jsize>(bytes.size()), reinterpret_cast<jbyte *>(bytes.data()));
  return coreLib.avatarUseImage(bytes.data(), bytes.size()) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_armsx3_core_ARMSX3_usbSetPassthrough(JNIEnv *env, jobject, jstring fds) {
  if (!coreLib.usbSetPassthrough) return;

  const char *c = fds ? env->GetStringUTFChars(fds, nullptr) : nullptr;
  const std::string value = c ? c : "";
  if (c) env->ReleaseStringUTFChars(fds, c);

  coreLib.usbSetPassthrough(value);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_armsx3_core_ARMSX3_usbPassedThrough(JNIEnv *, jobject) {
  return coreLib.usbPassedThrough ? coreLib.usbPassedThrough() : 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_armsx3_core_ARMSX3_takeGameError(JNIEnv *env, jobject) {
  const char *reason = coreLib.takeGameError ? coreLib.takeGameError() : nullptr;
  return env->NewStringUTF(reason ? reason : "");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_armsx3_core_ARMSX3_rpcnAddHost(JNIEnv *env, jobject, jstring desc, jstring host) {
  if (!coreLib.rpcnAddHost) return rpcn_unavailable(env);

  auto str = [&](jstring s) -> std::string {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
  };

  const std::string d = str(desc), h = str(host);
  const char *msg = coreLib.rpcnAddHost(d, h);
  return env->NewStringUTF(msg ? msg : "");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_armsx3_core_ARMSX3_rpcnDelHost(JNIEnv *env, jobject, jstring desc, jstring host) {
  if (!coreLib.rpcnDelHost) return rpcn_unavailable(env);

  auto str = [&](jstring s) -> std::string {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
  };

  const std::string d = str(desc), h = str(host);
  const char *msg = coreLib.rpcnDelHost(d, h);
  return env->NewStringUTF(msg ? msg : "");
}

extern "C" JNIEXPORT void JNICALL
Java_com_armsx3_core_ARMSX3_rpcnResetHosts(JNIEnv *, jobject) {
  if (coreLib.rpcnResetHosts) coreLib.rpcnResetHosts();
}

extern "C" JNIEXPORT void JNICALL
Java_com_armsx3_core_ARMSX3_rpcnSetIpv6(JNIEnv *, jobject, jboolean enabled) {
  if (coreLib.rpcnSetIpv6) coreLib.rpcnSetIpv6(enabled == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_armsx3_core_ARMSX3_rpcnSetConfig(JNIEnv *env, jobject, jstring host, jstring npid,
                                   jstring password, jstring token) {
  if (!coreLib.rpcnSetConfig) return;

  auto str = [&](jstring s) -> std::string {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
  };

  const std::string h = str(host), n = str(npid), p = str(password), t = str(token);
  coreLib.rpcnSetConfig(h, n, p, t);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_armsx3_core_ARMSX3_rpcnAddFriend(JNIEnv *env, jobject, jstring npid) {
  if (!coreLib.rpcnAddFriend) return rpcn_unavailable(env);

  const char *c = npid ? env->GetStringUTFChars(npid, nullptr) : nullptr;
  const std::string name = c ? c : "";
  if (c) env->ReleaseStringUTFChars(npid, c);

  const char *msg = coreLib.rpcnAddFriend(name);
  return env->NewStringUTF(msg ? msg : "");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_armsx3_core_ARMSX3_rpcnRemoveFriend(JNIEnv *env, jobject, jstring npid) {
  if (!coreLib.rpcnRemoveFriend) return rpcn_unavailable(env);

  const char *c = npid ? env->GetStringUTFChars(npid, nullptr) : nullptr;
  const std::string name = c ? c : "";
  if (c) env->ReleaseStringUTFChars(npid, c);

  const char *msg = coreLib.rpcnRemoveFriend(name);
  return env->NewStringUTF(msg ? msg : "");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_armsx3_core_ARMSX3_rpcnGetFriends(JNIEnv *env, jobject) {
  if (!coreLib.rpcnGetFriends) return env->NewStringUTF("{}");

  const char *msg = coreLib.rpcnGetFriends();
  return env->NewStringUTF(msg ? msg : "{}");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_armsx3_core_ARMSX3_rpcnGetInvites(JNIEnv *env, jobject) {
  if (!coreLib.rpcnGetInvites) return env->NewStringUTF("[]");

  const char *msg = coreLib.rpcnGetInvites();
  return env->NewStringUTF(msg ? msg : "[]");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_armsx3_core_ARMSX3_rpcnAcceptInvite(JNIEnv *env, jobject, jlong id) {
  if (!coreLib.rpcnAcceptInvite) return rpcn_unavailable(env);

  const char *msg = coreLib.rpcnAcceptInvite(static_cast<uint64_t>(id));
  return env->NewStringUTF(msg ? msg : "");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_armsx3_core_ARMSX3_rpcnDeclineInvite(JNIEnv *env, jobject, jlong id) {
  if (!coreLib.rpcnDeclineInvite) return rpcn_unavailable(env);

  const char *msg = coreLib.rpcnDeclineInvite(static_cast<uint64_t>(id));
  return env->NewStringUTF(msg ? msg : "");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_armsx3_core_ARMSX3_rpcnFriendsSession(JNIEnv *env, jobject, jboolean open) {
  if (!coreLib.rpcnFriendsSession) return rpcn_unavailable(env);

  const char *msg = coreLib.rpcnFriendsSession(open == JNI_TRUE);
  return env->NewStringUTF(msg ? msg : "");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_armsx3_core_ARMSX3_rpcnCreateAccount(JNIEnv *env, jobject, jstring npid,
                                       jstring password, jstring onlineName,
                                       jstring email) {
  if (!coreLib.rpcnCreateAccount) return rpcn_unavailable(env);

  auto str = [&](jstring s) -> std::string {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
  };

  const std::string n = str(npid), p = str(password), o = str(onlineName), e = str(email);
  const char *msg = coreLib.rpcnCreateAccount(n, p, o, e);
  return env->NewStringUTF(msg ? msg : "");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_armsx3_core_ARMSX3_rpcnResendToken(JNIEnv *env, jobject, jstring npid,
                                     jstring password) {
  if (!coreLib.rpcnResendToken) return rpcn_unavailable(env);

  auto str = [&](jstring s) -> std::string {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
  };

  const std::string n = str(npid), p = str(password);
  const char *msg = coreLib.rpcnResendToken(n, p);
  return env->NewStringUTF(msg ? msg : "");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_armsx3_core_ARMSX3_rpcnSendResetToken(JNIEnv *env, jobject, jstring npid,
                                        jstring email) {
  if (!coreLib.rpcnSendResetToken) return rpcn_unavailable(env);

  auto str = [&](jstring s) -> std::string {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
  };

  const std::string n = str(npid), e = str(email);
  const char *msg = coreLib.rpcnSendResetToken(n, e);
  return env->NewStringUTF(msg ? msg : "");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_armsx3_core_ARMSX3_rpcnResetPassword(JNIEnv *env, jobject, jstring npid, jstring token,
                                       jstring password) {
  if (!coreLib.rpcnResetPassword) return rpcn_unavailable(env);

  auto str = [&](jstring s) -> std::string {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
  };

  const std::string n = str(npid), t = str(token), p = str(password);
  const char *msg = coreLib.rpcnResetPassword(n, t, p);
  return env->NewStringUTF(msg ? msg : "");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_armsx3_core_ARMSX3_rpcnSignIn(JNIEnv *env, jobject, jstring host, jstring npid, jstring password,
                                jstring token) {
  if (!coreLib.rpcnSignIn) return rpcn_unavailable(env);

  auto str = [&](jstring s) -> std::string {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
  };

  const std::string h = str(host), n = str(npid), p = str(password), t = str(token);
  const char *msg = coreLib.rpcnSignIn(h, n, p, t);
  return env->NewStringUTF(msg ? msg : "");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_armsx3_core_ARMSX3_rpcnTestLogin(JNIEnv *env, jobject) {
  if (!coreLib.rpcnTestLogin) return rpcn_unavailable(env);
  const char *msg = coreLib.rpcnTestLogin();
  return env->NewStringUTF(msg ? msg : "");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_armsx3_core_ARMSX3_rpcnDeleteTrophies(JNIEnv *env, jobject) {
  if (!coreLib.rpcnDeleteTrophies) return rpcn_unavailable(env);
  const char *msg = coreLib.rpcnDeleteTrophies();
  return env->NewStringUTF(msg ? msg : "");
}
