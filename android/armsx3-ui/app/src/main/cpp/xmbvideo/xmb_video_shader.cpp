// XMB mode's video shaders: a video's picture through a RetroArch (.slangp) shader chain.
//
// The decoder (Android's MediaPlayer, or the core's FFmpeg player) writes into an AImageReader
// rather than onto the screen. Each frame's AHardwareBuffer is imported into Vulkan, YUV through
// a sampler YCbCr conversion and RGBA as it is, and drawn upright into an RGBA picture the size
// of the video. librashader runs the chain from that picture onto the player's own surface.
//
// librashader.so is dlopen()ed through librashader_ld.h, its permissive loader header, as the
// core does for games (rpcs3/Emu/RSX/VK/upscalers/librashader_pass.h), so nothing MPL links in.
//
// The system's Vulkan driver, not a custom one a game may use: this is its own instance and
// device, made when a shader is picked and gone when the video closes.

#include <android/hardware_buffer.h>
#include <dlfcn.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <media/NdkImage.h>
#include <media/NdkImageReader.h>
#include <poll.h>
#include <unistd.h>

#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>

#define LIBRA_RUNTIME_VULKAN
#include <librashader_ld.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <utility>
#include <vector>

namespace {

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "XmbVideoShader", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "XmbVideoShader", __VA_ARGS__)

// The picture pass, compiled from picture.vert and picture.frag beside this file:
//   glslc -O --target-env=vulkan1.1 -mfmt=num picture.vert -o picture.vert.inc (and .frag)
const uint32_t kVert[] = {
#include "picture.vert.inc"
};
const uint32_t kFrag[] = {
#include "picture.frag.inc"
};

// A frame buffer's identity for the import cache. AHardwareBuffer_getId came with Android 12, so it is
// looked up rather than linked (the a11 and legacy builds run on Android 11). Before it, the buffer's
// address serves: each import holds a reference to its buffer, so no other buffer can take that
// address while it is cached.
uint64_t bufferKey(AHardwareBuffer* buffer) {
  using GetId = int (*)(const AHardwareBuffer*, uint64_t*);
  static const GetId get_id = [] {
    void* lib = dlopen("libnativewindow.so", RTLD_NOW);
    return lib != nullptr ? reinterpret_cast<GetId>(dlsym(lib, "AHardwareBuffer_getId")) : nullptr;
  }();
  uint64_t id = 0;
  if (get_id != nullptr && get_id(buffer, &id) == 0) return id;
  return reinterpret_cast<uintptr_t>(buffer);
}

const libra_instance_t& libra() {
  static const libra_instance_t instance = librashader_load_instance();
  return instance;
}

// A librashader error, logged with what failed and then freed. True when there was one.
bool failedWith(libra_error_t err, const char* what, const std::string& preset) {
  if (err == nullptr) return false;
  char* text = nullptr;
  if (libra().error_write(err, &text) == 0 && text != nullptr) {
    LOGW("%s (%s): %s", what, preset.c_str(), text);
    libra().error_free_string(&text);
  } else {
    LOGW("%s (%s)", what, preset.c_str());
  }
  libra().error_free(&err);
  return true;
}

// A frame's buffer as Vulkan sees it, kept while the reader keeps cycling the same buffers.
struct Imported {
  VkImage image = VK_NULL_HANDLE;
  VkDeviceMemory memory = VK_NULL_HANDLE;
  VkImageView view = VK_NULL_HANDLE;
  uint64_t key = 0;  // the conversion it was made for
  uint64_t used = 0; // the last frame it was drawn
};

// How a buffer's pixels are read: its format (or the driver's external one) and, for YUV, the
// conversion to RGB. The sampler is fixed into the pass's layout, so each of these has its own.
struct Reading {
  uint64_t key = ~0ull;
  VkSamplerYcbcrConversion conversion = VK_NULL_HANDLE;
  VkSampler sampler = VK_NULL_HANDLE;
  VkDescriptorSetLayout setLayout = VK_NULL_HANDLE;
  VkDescriptorPool pool = VK_NULL_HANDLE;
  VkDescriptorSet set = VK_NULL_HANDLE;
  VkPipelineLayout layout = VK_NULL_HANDLE;
  VkPipeline pipeline = VK_NULL_HANDLE;
};

class Stage {
 public:
  Stage(ANativeWindow* output, bool rgba, int width, int height) : output_(output) {
    const uint64_t usage = rgba ? (AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN)
                                : AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
    if (AImageReader_newWithUsage(width > 0 ? width : 1920, height > 0 ? height : 1080,
                                  rgba ? AIMAGE_FORMAT_RGBA_8888 : AIMAGE_FORMAT_PRIVATE, usage, 4,
                                  &reader_) != AMEDIA_OK) {
      reader_ = nullptr;
      failed_ = true;
      return;
    }
    listener_.context = this;
    listener_.onImageAvailable = [](void* context, AImageReader*) {
      auto* s = static_cast<Stage*>(context);
      std::lock_guard lock(s->m_);
      s->frames_++;
      s->cv_.notify_one();
    };
    AImageReader_setImageListener(reader_, &listener_);
    AImageReader_getWindow(reader_, &input_);
    thread_ = std::thread([this] { run(); });
  }

  ~Stage() {
    {
      std::lock_guard lock(m_);
      stop_ = true;
      cv_.notify_one();
    }
    if (thread_.joinable()) thread_.join();
    if (reader_ != nullptr) {
      AImageReader_setImageListener(reader_, nullptr);
      AImageReader_delete(reader_);
    }
    if (output_ != nullptr) ANativeWindow_release(output_);
  }

  ANativeWindow* input() const { return input_; }
  bool failed() const { return failed_.load(); }

  void preset(std::string path, std::vector<std::pair<std::string, float>> params) {
    std::lock_guard lock(m_);
    wantPreset_ = std::move(path);
    wantParams_ = std::move(params);
    presetDirty_ = true;
    cv_.notify_one();
  }

  void rotation(int degrees) {
    std::lock_guard lock(m_);
    rotation_ = ((degrees % 360) + 360) % 360;
  }

  // Drawn again from the last frame: the surface changed size while the video is paused.
  void redraw() {
    std::lock_guard lock(m_);
    redrawWanted_ = true;
    cv_.notify_one();
  }

 private:
  // ---- the thread: Vulkan made here, every frame drawn here, all of it let go here ----
  void run() {
    if (!initVulkan()) {
      LOGW("no Vulkan for video shaders");
      failed_ = true;
      teardown();
      return;
    }
    uint64_t seen = 0;
    auto waiting = std::chrono::steady_clock::now();
    while (true) {
      bool redraw = false;
      {
        std::unique_lock lock(m_);
        cv_.wait_for(lock, std::chrono::milliseconds(100),
                     [&] { return stop_ || frames_ != seen || presetDirty_ || redrawWanted_; });
        if (stop_) break;
        if (presetDirty_) {
          presetDirty_ = false;
          preset_ = wantPreset_;
          params_ = wantParams_;
          chainDirty_ = true;
        }
        redraw = redrawWanted_;
        redrawWanted_ = false;
        seen = frames_;
      }
      if (chainDirty_) {
        chainDirty_ = false;
        if (!makeChain()) {
          failed_ = true;
          break;
        }
        redraw = true;
      }
      // Frames are taken (and drawn into the picture) before the screen is ours too, so the
      // decoder never waits on the stage.
      const bool fresh = takeFrame();
      if (failed_) break;
      if (swapchain_ == VK_NULL_HANDLE) {
        if (!makeSwapchain()) {
          // The decoder lets go of the surface a moment after it is handed the stage's input; a
          // surface still not free after three seconds will not be.
          if (std::chrono::steady_clock::now() - waiting > std::chrono::seconds(3)) {
            LOGW("the surface never came free");
            failed_ = true;
            break;
          }
          continue;
        }
        redraw = true;
      }
      // Only through a chain: a frame can come before the preset it is to go through.
      if ((fresh || redraw) && pictureReady_ && chain_ != nullptr) present();
      if (failed_) break;
      if (swapchain_ == VK_NULL_HANDLE) waiting = std::chrono::steady_clock::now();
    }
    teardown();
  }

  bool check(VkResult r, const char* what) {
    if (r == VK_SUCCESS) return true;
    LOGW("%s failed (%d)", what, r);
    return false;
  }

  bool initVulkan() {
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "ARMSX3 video shaders";
    app.apiVersion = VK_API_VERSION_1_1;
    const char* instExt[] = {VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
    VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ici.pApplicationInfo = &app;
    ici.enabledExtensionCount = 2;
    ici.ppEnabledExtensionNames = instExt;
    if (!check(vkCreateInstance(&ici, nullptr, &instance_), "vkCreateInstance")) return false;

    // The surface is the decoder's until it lets go of it, a moment after it is handed the stage's
    // input: tried again for a while before giving up.
    VkAndroidSurfaceCreateInfoKHR sci{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
    sci.window = output_;
    for (int tries = 0;; tries++) {
      const VkResult r = vkCreateAndroidSurfaceKHR(instance_, &sci, nullptr, &surface_);
      if (r == VK_SUCCESS) break;
      surface_ = VK_NULL_HANDLE;
      bool stopping;
      {
        std::lock_guard lock(m_);
        stopping = stop_;
      }
      if (r != VK_ERROR_NATIVE_WINDOW_IN_USE_KHR || tries >= 100 || stopping) {
        LOGW("vkCreateAndroidSurfaceKHR failed (%d)", r);
        return false;
      }
      std::this_thread::sleep_for(std::chrono::milliseconds(30));
    }

    uint32_t n = 0;
    vkEnumeratePhysicalDevices(instance_, &n, nullptr);
    std::vector<VkPhysicalDevice> gpus(n);
    vkEnumeratePhysicalDevices(instance_, &n, gpus.data());
    const char* devExt[] = {
        VK_KHR_SWAPCHAIN_EXTENSION_NAME,
        VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME,
        VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME,
    };
    for (VkPhysicalDevice g : gpus) {
      VkPhysicalDeviceProperties props;
      vkGetPhysicalDeviceProperties(g, &props);
      if (props.apiVersion < VK_API_VERSION_1_1) continue;
      uint32_t e = 0;
      vkEnumerateDeviceExtensionProperties(g, nullptr, &e, nullptr);
      std::vector<VkExtensionProperties> exts(e);
      vkEnumerateDeviceExtensionProperties(g, nullptr, &e, exts.data());
      bool all = true;
      for (const char* want : devExt) {
        bool found = false;
        for (const auto& x : exts) found = found || std::strcmp(x.extensionName, want) == 0;
        all = all && found;
      }
      if (!all) continue;
      VkPhysicalDeviceSamplerYcbcrConversionFeatures ycbcr{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SAMPLER_YCBCR_CONVERSION_FEATURES};
      VkPhysicalDeviceFeatures2 features{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
      features.pNext = &ycbcr;
      vkGetPhysicalDeviceFeatures2(g, &features);
      if (!ycbcr.samplerYcbcrConversion) continue;
      uint32_t q = 0;
      vkGetPhysicalDeviceQueueFamilyProperties(g, &q, nullptr);
      std::vector<VkQueueFamilyProperties> families(q);
      vkGetPhysicalDeviceQueueFamilyProperties(g, &q, families.data());
      for (uint32_t i = 0; i < q; i++) {
        VkBool32 present = VK_FALSE;
        vkGetPhysicalDeviceSurfaceSupportKHR(g, i, surface_, &present);
        if ((families[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) && present) {
          gpu_ = g;
          family_ = i;
          break;
        }
      }
      if (gpu_ != VK_NULL_HANDLE) break;
    }
    if (gpu_ == VK_NULL_HANDLE) {
      LOGW("no GPU with hardware-buffer import, YCbCr sampling and a present queue");
      return false;
    }

    const float priority = 1.0f;
    VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    qci.queueFamilyIndex = family_;
    qci.queueCount = 1;
    qci.pQueuePriorities = &priority;
    VkPhysicalDeviceSamplerYcbcrConversionFeatures ycbcr{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SAMPLER_YCBCR_CONVERSION_FEATURES};
    ycbcr.samplerYcbcrConversion = VK_TRUE;
    VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    dci.pNext = &ycbcr;
    dci.queueCreateInfoCount = 1;
    dci.pQueueCreateInfos = &qci;
    dci.enabledExtensionCount = 3;
    dci.ppEnabledExtensionNames = devExt;
    if (!check(vkCreateDevice(gpu_, &dci, nullptr, &device_), "vkCreateDevice")) return false;
    vkGetDeviceQueue(device_, family_, 0, &queue_);
    getAhbProperties_ = reinterpret_cast<PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(
        vkGetDeviceProcAddr(device_, "vkGetAndroidHardwareBufferPropertiesANDROID"));
    if (getAhbProperties_ == nullptr) return false;

    VkCommandPoolCreateInfo pci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    pci.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    pci.queueFamilyIndex = family_;
    if (!check(vkCreateCommandPool(device_, &pci, nullptr, &pool_), "vkCreateCommandPool")) return false;
    VkCommandBufferAllocateInfo cai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    cai.commandPool = pool_;
    cai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cai.commandBufferCount = 1;
    if (!check(vkAllocateCommandBuffers(device_, &cai, &cmd_), "vkAllocateCommandBuffers")) return false;
    VkFenceCreateInfo fci{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    if (!check(vkCreateFence(device_, &fci, nullptr, &fence_), "vkCreateFence")) return false;
    VkSemaphoreCreateInfo semi{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    if (!check(vkCreateSemaphore(device_, &semi, nullptr, &acquired_), "vkCreateSemaphore")) return false;
    if (!check(vkCreateSemaphore(device_, &semi, nullptr, &rendered_), "vkCreateSemaphore")) return false;

    // The picture's pass: drawn whole (nothing to keep), then read by the chain's first pass.
    VkAttachmentDescription att{};
    att.format = VK_FORMAT_R8G8B8A8_UNORM;
    att.samples = VK_SAMPLE_COUNT_1_BIT;
    att.loadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    att.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    att.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    att.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    att.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    att.finalLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    VkAttachmentReference ref{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkSubpassDescription sub{};
    sub.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    sub.colorAttachmentCount = 1;
    sub.pColorAttachments = &ref;
    VkSubpassDependency deps[2]{};
    deps[0].srcSubpass = VK_SUBPASS_EXTERNAL;
    deps[0].dstSubpass = 0;
    deps[0].srcStageMask = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
    deps[0].dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    deps[0].srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
    deps[0].dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    deps[1].srcSubpass = 0;
    deps[1].dstSubpass = VK_SUBPASS_EXTERNAL;
    deps[1].srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    deps[1].dstStageMask = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
    deps[1].srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    deps[1].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    VkRenderPassCreateInfo rpi{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
    rpi.attachmentCount = 1;
    rpi.pAttachments = &att;
    rpi.subpassCount = 1;
    rpi.pSubpasses = &sub;
    rpi.dependencyCount = 2;
    rpi.pDependencies = deps;
    if (!check(vkCreateRenderPass(device_, &rpi, nullptr, &pass_), "vkCreateRenderPass")) return false;

    VkShaderModuleCreateInfo smi{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    smi.codeSize = sizeof(kVert);
    smi.pCode = kVert;
    if (!check(vkCreateShaderModule(device_, &smi, nullptr, &vert_), "vert module")) return false;
    smi.codeSize = sizeof(kFrag);
    smi.pCode = kFrag;
    if (!check(vkCreateShaderModule(device_, &smi, nullptr, &frag_), "frag module")) return false;
    return true;
  }

  uint32_t memoryType(uint32_t bits, VkMemoryPropertyFlags want) {
    VkPhysicalDeviceMemoryProperties mp;
    vkGetPhysicalDeviceMemoryProperties(gpu_, &mp);
    for (uint32_t i = 0; i < mp.memoryTypeCount; i++)
      if ((bits & (1u << i)) && (mp.memoryTypes[i].propertyFlags & want) == want) return i;
    for (uint32_t i = 0; i < mp.memoryTypeCount; i++)
      if (bits & (1u << i)) return i;
    return 0;
  }

  // ---- the output: a swapchain on the player's surface ----
  bool makeSwapchain() {
    VkSurfaceCapabilitiesKHR caps;
    if (vkGetPhysicalDeviceSurfaceCapabilitiesKHR(gpu_, surface_, &caps) != VK_SUCCESS) return false;
    uint32_t n = 0;
    vkGetPhysicalDeviceSurfaceFormatsKHR(gpu_, surface_, &n, nullptr);
    std::vector<VkSurfaceFormatKHR> formats(n);
    vkGetPhysicalDeviceSurfaceFormatsKHR(gpu_, surface_, &n, formats.data());
    if (formats.empty()) return false;
    VkSurfaceFormatKHR fmt = formats[0];
    for (const auto& f : formats)
      if (f.format == VK_FORMAT_R8G8B8A8_UNORM && f.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) fmt = f;
    VkExtent2D extent = caps.currentExtent;
    if (extent.width == 0xFFFFFFFFu) {
      extent.width = static_cast<uint32_t>(ANativeWindow_getWidth(output_));
      extent.height = static_cast<uint32_t>(ANativeWindow_getHeight(output_));
    }
    if (extent.width == 0 || extent.height == 0) return false;
    VkCompositeAlphaFlagBitsKHR alpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    if (!(caps.supportedCompositeAlpha & alpha)) alpha = VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    VkSwapchainCreateInfoKHR sci{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
    sci.surface = surface_;
    sci.minImageCount = std::max(caps.minImageCount, 3u);
    if (caps.maxImageCount != 0) sci.minImageCount = std::min(sci.minImageCount, caps.maxImageCount);
    sci.imageFormat = fmt.format;
    sci.imageColorSpace = fmt.colorSpace;
    sci.imageExtent = extent;
    sci.imageArrayLayers = 1;
    sci.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    sci.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    sci.preTransform = (caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)
                           ? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR
                           : caps.currentTransform;
    sci.compositeAlpha = alpha;
    sci.presentMode = VK_PRESENT_MODE_FIFO_KHR;
    sci.clipped = VK_TRUE;
    sci.oldSwapchain = swapchain_;
    VkSwapchainKHR made = VK_NULL_HANDLE;
    const VkResult r = vkCreateSwapchainKHR(device_, &sci, nullptr, &made);
    if (swapchain_ != VK_NULL_HANDLE) {
      vkDestroySwapchainKHR(device_, swapchain_, nullptr);
      // A present that failed can leave its semaphore signalled; the next frame starts clean.
      VkSemaphoreCreateInfo semi{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
      vkDestroySemaphore(device_, acquired_, nullptr);
      vkDestroySemaphore(device_, rendered_, nullptr);
      acquired_ = rendered_ = VK_NULL_HANDLE;
      if (vkCreateSemaphore(device_, &semi, nullptr, &acquired_) != VK_SUCCESS ||
          vkCreateSemaphore(device_, &semi, nullptr, &rendered_) != VK_SUCCESS) {
        if (made != VK_NULL_HANDLE) vkDestroySwapchainKHR(device_, made, nullptr);
        swapchain_ = VK_NULL_HANDLE;
        failed_ = true;
        return false;
      }
    }
    swapchain_ = VK_NULL_HANDLE;
    if (r != VK_SUCCESS) {
      // VK_ERROR_NATIVE_WINDOW_IN_USE_KHR while the decoder still has the surface.
      if (r != VK_ERROR_NATIVE_WINDOW_IN_USE_KHR) LOGW("vkCreateSwapchainKHR failed (%d)", r);
      return false;
    }
    swapchain_ = made;
    swapFormat_ = fmt.format;
    swapExtent_ = extent;
    vkGetSwapchainImagesKHR(device_, swapchain_, &n, nullptr);
    swapImages_.resize(n);
    vkGetSwapchainImagesKHR(device_, swapchain_, &n, swapImages_.data());
    LOGI("presenting %ux%u, %u images", extent.width, extent.height, n);
    return true;
  }

  // ---- the chain ----
  bool makeChain() {
    if (chain_ != nullptr) {
      vkDeviceWaitIdle(device_);
      libra().vk_filter_chain_free(&chain_);
      chain_ = nullptr;
    }
    if (!libra().instance_loaded) {
      LOGW("librashader.so is not there");
      return false;
    }
    libra_shader_preset_t preset = nullptr;
    if (failedWith(libra().preset_create(preset_.c_str(), &preset), "preset would not load", preset_) || preset == nullptr)
      return false;
    libra_device_vk_t vk{};
    vk.physical_device = gpu_;
    vk.instance = instance_;
    vk.device = device_;
    vk.queue = queue_;
    vk.entry = vkGetInstanceProcAddr;
    if (failedWith(libra().vk_filter_chain_create(&preset, vk, nullptr, &chain_), "chain would not build", preset_) ||
        chain_ == nullptr) {
      // As the game pass does: the preset is still ours when the chain is not made.
      libra().preset_free(&preset);
      chain_ = nullptr;
      return false;
    }
    // The game settings' tuning of this preset, as games get it.
    for (const auto& [name, value] : params_)
      failedWith(libra().vk_filter_chain_set_param(&chain_, name.c_str(), value), "parameter", name);
    frameCount_ = 0;
    LOGI("chain %s", preset_.c_str());
    return true;
  }

  // ---- a frame in: its buffer imported, drawn upright into the picture ----
  bool takeFrame() {
    AImage* image = nullptr;
    int fence = -1;
    if (AImageReader_acquireLatestImageAsync(reader_, &image, &fence) != AMEDIA_OK || image == nullptr) return false;
    if (fence >= 0) {
      pollfd p{fence, POLLIN, 0};
      poll(&p, 1, 1000);
      close(fence);
    }
    AHardwareBuffer* buffer = nullptr;
    AImageCropRect crop{};
    int32_t bw = 0, bh = 0;
    AImage_getHardwareBuffer(image, &buffer);
    AImage_getCropRect(image, &crop);
    AImage_getWidth(image, &bw);
    AImage_getHeight(image, &bh);
    bool ok = buffer != nullptr && bw > 0 && bh > 0;
    if (ok) ok = drawPicture(buffer, crop, bw, bh);
    AImage_delete(image);
    if (!ok) failed_ = true;
    return ok;
  }

  Imported* importBuffer(AHardwareBuffer* buffer, Reading*& reading) {
    const uint64_t id = bufferKey(buffer);
    auto it = imported_.find(id);
    if (it != imported_.end()) {
      reading = &readings_[it->second.key];
      return &it->second;
    }

    VkAndroidHardwareBufferFormatPropertiesANDROID fp{VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID};
    VkAndroidHardwareBufferPropertiesANDROID props{VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID};
    props.pNext = &fp;
    if (!check(getAhbProperties_(device_, buffer, &props), "vkGetAndroidHardwareBufferPropertiesANDROID")) return nullptr;
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(buffer, &desc);

    reading = readingFor(fp);
    if (reading == nullptr) return nullptr;
    const bool external = fp.format == VK_FORMAT_UNDEFINED;

    Imported im;
    im.key = reading->key;
    VkExternalFormatANDROID ext{VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID};
    ext.externalFormat = external ? fp.externalFormat : 0;
    VkExternalMemoryImageCreateInfo emi{VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO};
    emi.pNext = &ext;
    emi.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
    VkImageCreateInfo ici{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ici.pNext = &emi;
    ici.imageType = VK_IMAGE_TYPE_2D;
    ici.format = fp.format;
    ici.extent = {desc.width, desc.height, 1};
    ici.mipLevels = 1;
    ici.arrayLayers = 1;
    ici.samples = VK_SAMPLE_COUNT_1_BIT;
    ici.tiling = VK_IMAGE_TILING_OPTIMAL;
    ici.usage = VK_IMAGE_USAGE_SAMPLED_BIT;
    ici.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ici.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (!check(vkCreateImage(device_, &ici, nullptr, &im.image), "import vkCreateImage")) return nullptr;
    VkImportAndroidHardwareBufferInfoANDROID imp{VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID};
    imp.buffer = buffer;
    VkMemoryDedicatedAllocateInfo ded{VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};
    ded.pNext = &imp;
    ded.image = im.image;
    VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    mai.pNext = &ded;
    mai.allocationSize = props.allocationSize;
    mai.memoryTypeIndex = memoryType(props.memoryTypeBits, 0);
    if (!check(vkAllocateMemory(device_, &mai, nullptr, &im.memory), "import vkAllocateMemory") ||
        !check(vkBindImageMemory(device_, im.image, im.memory, 0), "import vkBindImageMemory")) {
      release(im);
      return nullptr;
    }
    VkSamplerYcbcrConversionInfo conv{VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO};
    conv.conversion = reading->conversion;
    VkImageViewCreateInfo vci{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    vci.pNext = reading->conversion != VK_NULL_HANDLE ? &conv : nullptr;
    vci.image = im.image;
    vci.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vci.format = fp.format;
    vci.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    if (!check(vkCreateImageView(device_, &vci, nullptr, &im.view), "import vkCreateImageView")) {
      release(im);
      return nullptr;
    }
    auto& slot = imported_[id];
    slot = im;
    return &slot;
  }

  void release(Imported& im) {
    if (im.view != VK_NULL_HANDLE) vkDestroyImageView(device_, im.view, nullptr);
    if (im.image != VK_NULL_HANDLE) vkDestroyImage(device_, im.image, nullptr);
    if (im.memory != VK_NULL_HANDLE) vkFreeMemory(device_, im.memory, nullptr);
    im = Imported{};
  }

  static bool plainRgb(VkFormat f) {
    switch (f) {
      case VK_FORMAT_R8G8B8A8_UNORM:
      case VK_FORMAT_R8G8B8_UNORM:
      case VK_FORMAT_B8G8R8A8_UNORM:
      case VK_FORMAT_R5G6B5_UNORM_PACK16:
      case VK_FORMAT_A2B10G10R10_UNORM_PACK32:
      case VK_FORMAT_R16G16B16A16_SFLOAT:
        return true;
      default:
        return false;
    }
  }

  // The sampler (with the conversion a YUV buffer needs) and the pass reading through it.
  Reading* readingFor(const VkAndroidHardwareBufferFormatPropertiesANDROID& fp) {
    const bool plain = plainRgb(fp.format);
    const uint64_t key = plain ? static_cast<uint64_t>(fp.format)
                               : (fp.format != VK_FORMAT_UNDEFINED ? static_cast<uint64_t>(fp.format) : fp.externalFormat) ^
                                     (static_cast<uint64_t>(fp.suggestedYcbcrModel) << 56) ^
                                     (static_cast<uint64_t>(fp.suggestedYcbcrRange) << 60);
    auto found = readings_.find(key);
    if (found != readings_.end()) return &found->second;

    Reading rd;
    rd.key = key;
    const VkFormatFeatureFlags needed = VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT |
                                        (plain ? 0 : VK_FORMAT_FEATURE_SAMPLED_IMAGE_YCBCR_CONVERSION_LINEAR_FILTER_BIT);
    const bool linear = (fp.formatFeatures & needed) == needed;
    const VkFilter filter = linear ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
    VkExternalFormatANDROID ext{VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID};
    ext.externalFormat = fp.format == VK_FORMAT_UNDEFINED ? fp.externalFormat : 0;
    VkSamplerYcbcrConversionInfo convInfo{VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO};
    if (!plain) {
      VkSamplerYcbcrConversionCreateInfo cci{VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_CREATE_INFO};
      cci.pNext = &ext;
      cci.format = fp.format;
      cci.ycbcrModel = fp.suggestedYcbcrModel;
      cci.ycbcrRange = fp.suggestedYcbcrRange;
      cci.components = fp.samplerYcbcrConversionComponents;
      cci.xChromaOffset = fp.suggestedXChromaOffset;
      cci.yChromaOffset = fp.suggestedYChromaOffset;
      cci.chromaFilter = filter;
      cci.forceExplicitReconstruction = VK_FALSE;
      if (!check(vkCreateSamplerYcbcrConversion(device_, &cci, nullptr, &rd.conversion), "vkCreateSamplerYcbcrConversion"))
        return nullptr;
      convInfo.conversion = rd.conversion;
    }
    VkSamplerCreateInfo si{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
    si.pNext = plain ? nullptr : &convInfo;
    si.magFilter = filter;
    si.minFilter = filter;
    si.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    si.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    si.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    si.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    si.maxLod = 0.0f;
    if (!check(vkCreateSampler(device_, &si, nullptr, &rd.sampler), "vkCreateSampler")) {
      destroy(rd);
      return nullptr;
    }
    VkDescriptorSetLayoutBinding b{};
    b.binding = 0;
    b.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    b.descriptorCount = 1;
    b.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
    b.pImmutableSamplers = &rd.sampler;
    VkDescriptorSetLayoutCreateInfo dli{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    dli.bindingCount = 1;
    dli.pBindings = &b;
    if (!check(vkCreateDescriptorSetLayout(device_, &dli, nullptr, &rd.setLayout), "vkCreateDescriptorSetLayout")) {
      destroy(rd);
      return nullptr;
    }
    // A YUV conversion can take several descriptors of the pool for its one binding.
    VkDescriptorPoolSize size{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 4};
    VkDescriptorPoolCreateInfo dpi{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    dpi.maxSets = 1;
    dpi.poolSizeCount = 1;
    dpi.pPoolSizes = &size;
    VkDescriptorSetAllocateInfo dai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    if (!check(vkCreateDescriptorPool(device_, &dpi, nullptr, &rd.pool), "vkCreateDescriptorPool")) {
      destroy(rd);
      return nullptr;
    }
    dai.descriptorPool = rd.pool;
    dai.descriptorSetCount = 1;
    dai.pSetLayouts = &rd.setLayout;
    if (!check(vkAllocateDescriptorSets(device_, &dai, &rd.set), "vkAllocateDescriptorSets")) {
      destroy(rd);
      return nullptr;
    }
    VkPushConstantRange pcr{VK_SHADER_STAGE_VERTEX_BIT, 0, sizeof(float) * 8};
    VkPipelineLayoutCreateInfo pli{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    pli.setLayoutCount = 1;
    pli.pSetLayouts = &rd.setLayout;
    pli.pushConstantRangeCount = 1;
    pli.pPushConstantRanges = &pcr;
    if (!check(vkCreatePipelineLayout(device_, &pli, nullptr, &rd.layout), "vkCreatePipelineLayout")) {
      destroy(rd);
      return nullptr;
    }
    VkPipelineShaderStageCreateInfo stages[2]{};
    stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
    stages[0].module = vert_;
    stages[0].pName = "main";
    stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
    stages[1].module = frag_;
    stages[1].pName = "main";
    VkPipelineVertexInputStateCreateInfo vin{VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
    VkPipelineInputAssemblyStateCreateInfo ia{VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};
    ia.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
    VkPipelineViewportStateCreateInfo vp{VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
    vp.viewportCount = 1;
    vp.scissorCount = 1;
    VkPipelineRasterizationStateCreateInfo rs{VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
    rs.polygonMode = VK_POLYGON_MODE_FILL;
    rs.cullMode = VK_CULL_MODE_NONE;
    rs.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
    rs.lineWidth = 1.0f;
    VkPipelineMultisampleStateCreateInfo ms{VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
    ms.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
    VkPipelineColorBlendAttachmentState cba{};
    cba.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
    VkPipelineColorBlendStateCreateInfo cb{VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
    cb.attachmentCount = 1;
    cb.pAttachments = &cba;
    const VkDynamicState dyn[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo ds{VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};
    ds.dynamicStateCount = 2;
    ds.pDynamicStates = dyn;
    VkGraphicsPipelineCreateInfo gpi{VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
    gpi.stageCount = 2;
    gpi.pStages = stages;
    gpi.pVertexInputState = &vin;
    gpi.pInputAssemblyState = &ia;
    gpi.pViewportState = &vp;
    gpi.pRasterizationState = &rs;
    gpi.pMultisampleState = &ms;
    gpi.pColorBlendState = &cb;
    gpi.pDynamicState = &ds;
    gpi.layout = rd.layout;
    gpi.renderPass = pass_;
    if (!check(vkCreateGraphicsPipelines(device_, VK_NULL_HANDLE, 1, &gpi, nullptr, &rd.pipeline), "vkCreateGraphicsPipelines")) {
      destroy(rd);
      return nullptr;
    }
    auto& slot = readings_[key];
    slot = rd;
    return &slot;
  }

  void destroy(Reading& rd) {
    if (rd.pipeline != VK_NULL_HANDLE) vkDestroyPipeline(device_, rd.pipeline, nullptr);
    if (rd.layout != VK_NULL_HANDLE) vkDestroyPipelineLayout(device_, rd.layout, nullptr);
    if (rd.pool != VK_NULL_HANDLE) vkDestroyDescriptorPool(device_, rd.pool, nullptr);
    if (rd.setLayout != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(device_, rd.setLayout, nullptr);
    if (rd.sampler != VK_NULL_HANDLE) vkDestroySampler(device_, rd.sampler, nullptr);
    if (rd.conversion != VK_NULL_HANDLE) vkDestroySamplerYcbcrConversion(device_, rd.conversion, nullptr);
    rd = Reading{};
  }

  // The picture, the video's own size turned upright: the chain's source.
  bool ensurePicture(uint32_t w, uint32_t h) {
    if (picture_ != VK_NULL_HANDLE && pictureW_ == w && pictureH_ == h) return true;
    if (picture_ != VK_NULL_HANDLE) {
      vkDeviceWaitIdle(device_);
      vkDestroyFramebuffer(device_, pictureFb_, nullptr);
      vkDestroyImageView(device_, pictureView_, nullptr);
      vkDestroyImage(device_, picture_, nullptr);
      vkFreeMemory(device_, pictureMem_, nullptr);
      picture_ = VK_NULL_HANDLE;
      pictureReady_ = false;
    }
    VkImageCreateInfo ici{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ici.imageType = VK_IMAGE_TYPE_2D;
    ici.format = VK_FORMAT_R8G8B8A8_UNORM;
    ici.extent = {w, h, 1};
    ici.mipLevels = 1;
    ici.arrayLayers = 1;
    ici.samples = VK_SAMPLE_COUNT_1_BIT;
    ici.tiling = VK_IMAGE_TILING_OPTIMAL;
    ici.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
    ici.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ici.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (!check(vkCreateImage(device_, &ici, nullptr, &picture_), "picture vkCreateImage")) return false;
    VkMemoryRequirements req;
    vkGetImageMemoryRequirements(device_, picture_, &req);
    VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    mai.allocationSize = req.size;
    mai.memoryTypeIndex = memoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (!check(vkAllocateMemory(device_, &mai, nullptr, &pictureMem_), "picture vkAllocateMemory")) return false;
    vkBindImageMemory(device_, picture_, pictureMem_, 0);
    VkImageViewCreateInfo vci{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    vci.image = picture_;
    vci.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vci.format = VK_FORMAT_R8G8B8A8_UNORM;
    vci.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    if (!check(vkCreateImageView(device_, &vci, nullptr, &pictureView_), "picture vkCreateImageView")) return false;
    VkFramebufferCreateInfo fci{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
    fci.renderPass = pass_;
    fci.attachmentCount = 1;
    fci.pAttachments = &pictureView_;
    fci.width = w;
    fci.height = h;
    fci.layers = 1;
    if (!check(vkCreateFramebuffer(device_, &fci, nullptr, &pictureFb_), "picture vkCreateFramebuffer")) return false;
    pictureW_ = w;
    pictureH_ = h;
    return true;
  }

  bool drawPicture(AHardwareBuffer* buffer, const AImageCropRect& crop, int32_t bw, int32_t bh) {
    Reading* rd = nullptr;
    Imported* im = importBuffer(buffer, rd);
    if (im == nullptr || rd == nullptr) return false;
    im->used = ++frameSerial_;
    forgetStale();

    int rot;
    {
      std::lock_guard lock(m_);
      rot = rotation_;
    }
    const int32_t cw = std::max(1, crop.right - crop.left);
    const int32_t ch = std::max(1, crop.bottom - crop.top);
    const bool sideways = rot == 90 || rot == 270;
    if (!ensurePicture(static_cast<uint32_t>(sideways ? ch : cw), static_cast<uint32_t>(sideways ? cw : ch))) return false;

    VkDescriptorImageInfo dii{};
    dii.imageView = im->view;
    dii.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    VkWriteDescriptorSet w{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
    w.dstSet = rd->set;
    w.dstBinding = 0;
    w.descriptorCount = 1;
    w.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    w.pImageInfo = &dii;
    vkUpdateDescriptorSets(device_, 1, &w, 0, nullptr);

    VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkResetCommandBuffer(cmd_, 0);
    vkBeginCommandBuffer(cmd_, &bi);
    // The buffer comes from the decoder: taken over from outside Vulkan, and handed back after.
    VkImageMemoryBarrier in{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    in.srcAccessMask = 0;
    in.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    in.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    in.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    in.srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
    in.dstQueueFamilyIndex = family_;
    in.image = im->image;
    in.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdPipelineBarrier(cmd_, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0,
                         nullptr, 1, &in);

    VkRenderPassBeginInfo rbi{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
    rbi.renderPass = pass_;
    rbi.framebuffer = pictureFb_;
    rbi.renderArea = {{0, 0}, {pictureW_, pictureH_}};
    vkCmdBeginRenderPass(cmd_, &rbi, VK_SUBPASS_CONTENTS_INLINE);
    vkCmdBindPipeline(cmd_, VK_PIPELINE_BIND_POINT_GRAPHICS, rd->pipeline);
    vkCmdBindDescriptorSets(cmd_, VK_PIPELINE_BIND_POINT_GRAPHICS, rd->layout, 0, 1, &rd->set, 0, nullptr);
    VkViewport v{0, 0, static_cast<float>(pictureW_), static_cast<float>(pictureH_), 0, 1};
    VkRect2D sc{{0, 0}, {pictureW_, pictureH_}};
    vkCmdSetViewport(cmd_, 0, 1, &v);
    vkCmdSetScissor(cmd_, 0, 1, &sc);
    // The visible part of the buffer, and the turn that sets it upright (a picture coordinate
    // c from the centre reads the buffer at turn * c).
    float pc[8] = {
        static_cast<float>(crop.left) / static_cast<float>(bw),
        static_cast<float>(crop.top) / static_cast<float>(bh),
        static_cast<float>(cw) / static_cast<float>(bw),
        static_cast<float>(ch) / static_cast<float>(bh),
        1, 0, 0, 1,
    };
    if (rot == 90) { pc[4] = 0; pc[5] = 1; pc[6] = -1; pc[7] = 0; }
    if (rot == 180) { pc[4] = -1; pc[5] = 0; pc[6] = 0; pc[7] = -1; }
    if (rot == 270) { pc[4] = 0; pc[5] = -1; pc[6] = 1; pc[7] = 0; }
    vkCmdPushConstants(cmd_, rd->layout, VK_SHADER_STAGE_VERTEX_BIT, 0, sizeof(pc), pc);
    vkCmdDraw(cmd_, 3, 1, 0, 0);
    vkCmdEndRenderPass(cmd_);

    VkImageMemoryBarrier out = in;
    out.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
    out.dstAccessMask = 0;
    out.oldLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    out.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    out.srcQueueFamilyIndex = family_;
    out.dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
    vkCmdPipelineBarrier(cmd_, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 0,
                         nullptr, 1, &out);
    vkEndCommandBuffer(cmd_);

    VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si.commandBufferCount = 1;
    si.pCommandBuffers = &cmd_;
    if (!check(vkQueueSubmit(queue_, 1, &si, fence_), "picture vkQueueSubmit")) return false;
    // Done with the buffer before the reader has it back.
    vkWaitForFences(device_, 1, &fence_, VK_TRUE, UINT64_MAX);
    vkResetFences(device_, 1, &fence_);
    pictureReady_ = true;
    return true;
  }

  // Buffers the reader no longer cycles (a new size, a new video) are let go after a while.
  void forgetStale() {
    if (imported_.size() <= 6) return;
    for (auto it = imported_.begin(); it != imported_.end();) {
      if (frameSerial_ - it->second.used > 30) {
        release(it->second);
        it = imported_.erase(it);
      } else {
        ++it;
      }
    }
  }

  // ---- the chain onto the screen ----
  void present() {
    uint32_t index = 0;
    VkResult r = vkAcquireNextImageKHR(device_, swapchain_, UINT64_MAX, acquired_, VK_NULL_HANDLE, &index);
    if (r == VK_ERROR_OUT_OF_DATE_KHR) {
      vkDeviceWaitIdle(device_);
      makeSwapchain();
      return;
    }
    if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) {
      LOGW("vkAcquireNextImageKHR failed (%d)", r);
      failed_ = true;
      return;
    }
    VkImage target = swapImages_[index];
    VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkResetCommandBuffer(cmd_, 0);
    vkBeginCommandBuffer(cmd_, &bi);
    VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    b.srcAccessMask = 0;
    b.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
    b.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    b.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image = target;
    b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdPipelineBarrier(cmd_, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr,
                         0, nullptr, 1, &b);
    // Black first: a chain that draws only its own area (a bezel's, a border's) leaves the rest.
    VkClearColorValue black{{0, 0, 0, 1}};
    vkCmdClearColorImage(cmd_, target, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &black, 1, &b.subresourceRange);
    b.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    b.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_READ_BIT;
    b.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    b.newLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
    vkCmdPipelineBarrier(cmd_, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, 0, 0, nullptr,
                         0, nullptr, 1, &b);

    libra_image_vk_t input{picture_, VK_FORMAT_R8G8B8A8_UNORM, pictureW_, pictureH_};
    libra_image_vk_t output{target, swapFormat_, swapExtent_.width, swapExtent_.height};
    libra_viewport_t viewport{0, 0, swapExtent_.width, swapExtent_.height};
    if (failedWith(libra().vk_filter_chain_frame(&chain_, cmd_, frameCount_++, input, output, &viewport, nullptr, nullptr),
                   "chain frame failed", preset_))
      failed_ = true;

    b.srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    b.dstAccessMask = 0;
    b.oldLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
    b.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    vkCmdPipelineBarrier(cmd_, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0,
                         nullptr, 0, nullptr, 1, &b);
    vkEndCommandBuffer(cmd_);

    const VkPipelineStageFlags wait = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si.waitSemaphoreCount = 1;
    si.pWaitSemaphores = &acquired_;
    si.pWaitDstStageMask = &wait;
    si.commandBufferCount = 1;
    si.pCommandBuffers = &cmd_;
    si.signalSemaphoreCount = 1;
    si.pSignalSemaphores = &rendered_;
    if (!check(vkQueueSubmit(queue_, 1, &si, fence_), "present vkQueueSubmit")) {
      failed_ = true;
      return;
    }
    VkPresentInfoKHR pi{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
    pi.waitSemaphoreCount = 1;
    pi.pWaitSemaphores = &rendered_;
    pi.swapchainCount = 1;
    pi.pSwapchains = &swapchain_;
    pi.pImageIndices = &index;
    r = vkQueuePresentKHR(queue_, &pi);
    vkWaitForFences(device_, 1, &fence_, VK_TRUE, UINT64_MAX);
    vkResetFences(device_, 1, &fence_);
    if (r == VK_ERROR_OUT_OF_DATE_KHR || r == VK_SUBOPTIMAL_KHR) {
      vkDeviceWaitIdle(device_);
      makeSwapchain();
    } else if (r != VK_SUCCESS) {
      LOGW("vkQueuePresentKHR failed (%d)", r);
      failed_ = true;
    }
  }

  void teardown() {
    if (device_ != VK_NULL_HANDLE) {
      vkDeviceWaitIdle(device_);
      if (chain_ != nullptr) libra().vk_filter_chain_free(&chain_);
      for (auto& [id, im] : imported_) release(im);
      imported_.clear();
      for (auto& [key, rd] : readings_) destroy(rd);
      readings_.clear();
      if (pictureFb_ != VK_NULL_HANDLE) vkDestroyFramebuffer(device_, pictureFb_, nullptr);
      if (pictureView_ != VK_NULL_HANDLE) vkDestroyImageView(device_, pictureView_, nullptr);
      if (picture_ != VK_NULL_HANDLE) vkDestroyImage(device_, picture_, nullptr);
      if (pictureMem_ != VK_NULL_HANDLE) vkFreeMemory(device_, pictureMem_, nullptr);
      if (swapchain_ != VK_NULL_HANDLE) vkDestroySwapchainKHR(device_, swapchain_, nullptr);
      if (vert_ != VK_NULL_HANDLE) vkDestroyShaderModule(device_, vert_, nullptr);
      if (frag_ != VK_NULL_HANDLE) vkDestroyShaderModule(device_, frag_, nullptr);
      if (pass_ != VK_NULL_HANDLE) vkDestroyRenderPass(device_, pass_, nullptr);
      if (acquired_ != VK_NULL_HANDLE) vkDestroySemaphore(device_, acquired_, nullptr);
      if (rendered_ != VK_NULL_HANDLE) vkDestroySemaphore(device_, rendered_, nullptr);
      if (fence_ != VK_NULL_HANDLE) vkDestroyFence(device_, fence_, nullptr);
      if (pool_ != VK_NULL_HANDLE) vkDestroyCommandPool(device_, pool_, nullptr);
      vkDestroyDevice(device_, nullptr);
    }
    if (surface_ != VK_NULL_HANDLE) vkDestroySurfaceKHR(instance_, surface_, nullptr);
    if (instance_ != VK_NULL_HANDLE) vkDestroyInstance(instance_, nullptr);
    device_ = VK_NULL_HANDLE;
    instance_ = VK_NULL_HANDLE;
  }

  ANativeWindow* output_ = nullptr;
  AImageReader* reader_ = nullptr;
  ANativeWindow* input_ = nullptr;
  AImageReader_ImageListener listener_{};
  std::thread thread_;
  std::mutex m_;
  std::condition_variable cv_;
  uint64_t frames_ = 0;
  bool stop_ = false;
  bool presetDirty_ = false;
  bool redrawWanted_ = false;
  std::string wantPreset_;
  std::vector<std::pair<std::string, float>> wantParams_;
  int rotation_ = 0;
  std::atomic<bool> failed_{false};

  // The thread's own.
  std::string preset_;
  std::vector<std::pair<std::string, float>> params_;
  bool chainDirty_ = false;
  VkInstance instance_ = VK_NULL_HANDLE;
  VkSurfaceKHR surface_ = VK_NULL_HANDLE;
  VkPhysicalDevice gpu_ = VK_NULL_HANDLE;
  uint32_t family_ = 0;
  VkDevice device_ = VK_NULL_HANDLE;
  VkQueue queue_ = VK_NULL_HANDLE;
  PFN_vkGetAndroidHardwareBufferPropertiesANDROID getAhbProperties_ = nullptr;
  VkCommandPool pool_ = VK_NULL_HANDLE;
  VkCommandBuffer cmd_ = VK_NULL_HANDLE;
  VkFence fence_ = VK_NULL_HANDLE;
  VkSemaphore acquired_ = VK_NULL_HANDLE;
  VkSemaphore rendered_ = VK_NULL_HANDLE;
  VkRenderPass pass_ = VK_NULL_HANDLE;
  VkShaderModule vert_ = VK_NULL_HANDLE;
  VkShaderModule frag_ = VK_NULL_HANDLE;
  VkSwapchainKHR swapchain_ = VK_NULL_HANDLE;
  VkFormat swapFormat_ = VK_FORMAT_UNDEFINED;
  VkExtent2D swapExtent_{};
  std::vector<VkImage> swapImages_;
  VkImage picture_ = VK_NULL_HANDLE;
  VkDeviceMemory pictureMem_ = VK_NULL_HANDLE;
  VkImageView pictureView_ = VK_NULL_HANDLE;
  VkFramebuffer pictureFb_ = VK_NULL_HANDLE;
  uint32_t pictureW_ = 0;
  uint32_t pictureH_ = 0;
  bool pictureReady_ = false;
  std::unordered_map<uint64_t, Imported> imported_;
  std::unordered_map<uint64_t, Reading> readings_;
  uint64_t frameSerial_ = 0;
  libra_vk_filter_chain_t chain_ = nullptr;
  size_t frameCount_ = 0;
};

std::string utf(JNIEnv* env, jstring s) {
  if (s == nullptr) return {};
  const char* c = env->GetStringUTFChars(s, nullptr);
  std::string out = c != nullptr ? c : "";
  if (c != nullptr) env->ReleaseStringUTFChars(s, c);
  return out;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_armsx2_ui_xmb_XmbVideoShader_nativeOpen(JNIEnv* env, jclass, jobject output, jboolean rgba,
                                                                          jint width, jint height) {
  ANativeWindow* window = ANativeWindow_fromSurface(env, output);
  if (window == nullptr) return 0;
  auto* s = new Stage(window, rgba == JNI_TRUE, width, height);
  if (s->failed() || s->input() == nullptr) {
    delete s;
    return 0;
  }
  return reinterpret_cast<jlong>(s);
}

JNIEXPORT jobject JNICALL Java_com_armsx2_ui_xmb_XmbVideoShader_nativeInput(JNIEnv* env, jclass, jlong handle) {
  auto* s = reinterpret_cast<Stage*>(handle);
  return s != nullptr && s->input() != nullptr ? ANativeWindow_toSurface(env, s->input()) : nullptr;
}

JNIEXPORT void JNICALL Java_com_armsx2_ui_xmb_XmbVideoShader_nativePreset(JNIEnv* env, jclass, jlong handle, jstring path,
                                                                          jobjectArray names, jfloatArray values) {
  auto* s = reinterpret_cast<Stage*>(handle);
  if (s == nullptr) return;
  std::vector<std::pair<std::string, float>> params;
  const jsize n = names != nullptr && values != nullptr ? std::min(env->GetArrayLength(names), env->GetArrayLength(values)) : 0;
  if (n > 0) {
    std::vector<jfloat> v(static_cast<size_t>(n));
    env->GetFloatArrayRegion(values, 0, n, v.data());
    for (jsize i = 0; i < n; i++) {
      auto* name = static_cast<jstring>(env->GetObjectArrayElement(names, i));
      params.emplace_back(utf(env, name), v[static_cast<size_t>(i)]);
      env->DeleteLocalRef(name);
    }
  }
  s->preset(utf(env, path), std::move(params));
}

JNIEXPORT void JNICALL Java_com_armsx2_ui_xmb_XmbVideoShader_nativeRotation(JNIEnv*, jclass, jlong handle, jint degrees) {
  auto* s = reinterpret_cast<Stage*>(handle);
  if (s != nullptr) s->rotation(degrees);
}

JNIEXPORT void JNICALL Java_com_armsx2_ui_xmb_XmbVideoShader_nativeRedraw(JNIEnv*, jclass, jlong handle) {
  auto* s = reinterpret_cast<Stage*>(handle);
  if (s != nullptr) s->redraw();
}

JNIEXPORT jboolean JNICALL Java_com_armsx2_ui_xmb_XmbVideoShader_nativeFailed(JNIEnv*, jclass, jlong handle) {
  auto* s = reinterpret_cast<Stage*>(handle);
  return s == nullptr || s->failed() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_armsx2_ui_xmb_XmbVideoShader_nativeClose(JNIEnv*, jclass, jlong handle) {
  delete reinterpret_cast<Stage*>(handle);
}

}  // extern "C"
