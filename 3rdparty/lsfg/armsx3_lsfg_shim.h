// C ABI for Lossless Scaling frame generation.
//
// framegen CANNOT be linked into libarmsx3-core.so. It links volk, which defines 655 globals
// named vkCreateImage, vkQueueSubmit, ... and 124 of those are byte-for-byte the names our own
// Vulkan loader declares in rpcs3/Emu/RSX/VK/vk_android_loader.h -- every single symbol the RSX
// renderer uses. Two ways that goes wrong, and the second is the one that costs a week:
//
//   1. duplicate symbol at link time (clang defaults to -fno-common), or
//   2. the linker merges them, and framegen's volkLoadDevice(itsOwnDevice) then repoints every
//      entry point the renderer uses at framegen's VkDevice. Every later vkCmdDraw goes to the
//      wrong device, and it presents as a driver crash with nothing pointing at frame generation.
//
// So framegen and volk live in their own libarmsx3_lsfg.so, reached by dlopen + dlsym through
// this header. Nothing here is C++: the CMake project builds ANDROID_STL=c++_static, so each .so
// carries its own libc++ and an std::vector or std::function crossing the boundary would be two
// unrelated types that happen to share a name. The shim builds those on its own side.
//
// framegen also throws (LSFG::vulkan_error and friends). Exceptions must not cross a dlopen
// boundary either, so every entry point here catches everything and returns a code; the message
// is retrievable with armsx3_lsfg_last_error().
#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// Bump when anything below changes shape. The loader refuses a library whose version it does not
// recognise, so a stale libarmsx3_lsfg.so on a user's device fails loudly at load instead of
// quietly passing mismatched structs.
#define ARMSX3_LSFG_ABI_VERSION 3u

// Mark the exported surface explicitly.
//
// The library is built -fvisibility=hidden so framegen's and volk's symbols stay in, and a
// version script narrows the dynamic table further. Neither of those can PROMOTE a symbol: a
// function hidden at compile time is local in the object, and `global:` in the linker script
// cannot bring it back. Without this attribute the .so builds and exports nothing at all, and
// the failure only shows up as dlsym returning null at runtime.
#if defined(__GNUC__) || defined(__clang__)
#define ARMSX3_LSFG_API __attribute__((visibility("default")))
#else
#define ARMSX3_LSFG_API
#endif

enum armsx3_lsfg_result
{
	ARMSX3_LSFG_OK = 0,
	ARMSX3_LSFG_ERR_UNKNOWN = -1,
	ARMSX3_LSFG_ERR_NOT_INITIALIZED = -2,
	ARMSX3_LSFG_ERR_BAD_ARGUMENT = -3,
	ARMSX3_LSFG_ERR_SHADERS = -4,
	ARMSX3_LSFG_ERR_VULKAN = -5,
};

// Hand back the SPIR-V for a named shader.
//
// framegen does NOT read Lossless.dll -- it asks for shaders by name and expects SPIR-V back.
// Extracting them from the user's own copy (PE resource -> DXBC -> SPIR-V) is the caller's job,
// which is deliberate: the shaders are THS's property and nothing here ships or downloads them.
//
// Return ARMSX3_LSFG_OK and set *out_data / *out_size on success. The buffer must stay valid
// until the initialize() call that triggered this returns. Any other return means "no such
// shader" and fails initialization.
typedef int (*armsx3_lsfg_shader_loader)(const char* name, const uint8_t** out_data,
	uint32_t* out_size, void* user);

// Version of the loaded library. Call first; anything else on a mismatched library is undefined.
ARMSX3_LSFG_API uint32_t armsx3_lsfg_abi_version(void);

// Bring up framegen on the adapter identified by device_uuid (VkPhysicalDeviceIDProperties
// deviceUUID, 16 bytes, passed as the first 8 -- that is what framegen matches on).
//
// framegen creates its OWN VkDevice on that adapter. It does not share ours, which is why images
// have to be handed over as AHardwareBuffer below rather than as VkImage.
// performance selects framegen's 3.1p shader family instead of 3.1: a cheaper pipeline at lower
// quality, which is the difference between usable and not on a mobile GPU. It is fixed for the
// lifetime of the library state -- every context, present and teardown after this call goes to the
// family chosen here, because the two keep separate contexts and separate device state.
//
// flow_scale is the optical-flow resolution as a fraction of full: 1.0 is upstream's default and
// lower is cheaper. Note the sense is inverted from upstream's own config file, which stores a
// divisor and passes 1.0f/value here.
ARMSX3_LSFG_API int armsx3_lsfg_initialize(uint64_t device_uuid, int is_hdr, float flow_scale,
	uint64_t generation_count, int performance, armsx3_lsfg_shader_loader loader, void* user);

// Create a context over a set of shared images.
//
// AHardwareBuffer rather than the FD path framegen also offers, because Adreno and Mali both
// refuse vkGetMemoryFdKHR(OPAQUE_FD) on AHB-imported memory -- the FD path simply does not work
// on the hardware this port runs on.
//
// The caller keeps ownership of every AHardwareBuffer and must keep them alive until the context
// is destroyed. Returns a context id >= 0, or a negative armsx3_lsfg_result.
ARMSX3_LSFG_API int32_t armsx3_lsfg_create_context_ahb(void* in0, void* in1, void* const* out_n,
	uint32_t out_count, uint32_t width, uint32_t height, int32_t format);

// Generate frames for one presented pair.
//
// Semaphores are sync file descriptors, not VkSemaphore: framegen is on a different device and a
// VkSemaphore handle would be meaningless to it. in_sem is waited on before generation starts;
// each out_sems[i] is signalled when output image i is ready. Pass -1 for an unused slot.
ARMSX3_LSFG_API int armsx3_lsfg_present(int32_t ctx, int in_sem, const int* out_sems, uint32_t out_count);

// Generate frames for one presented pair, and hand back a fence for the result.
//
// Identical to armsx3_lsfg_present in every respect except that *out_fence_fd receives a sync file
// descriptor that becomes readable once the generation this call submitted has finished. The
// caller owns that fd and must close(2) it.
//
// This is the answer to armsx3_lsfg_wait_idle() below being the only completion signal on offer.
// framegen renders on its OWN VkDevice, so the caller cannot wait on its queues; before this
// entry point existed the only way to know the generated images were ready -- and, more
// importantly, that framegen had finished READING the caller's input images -- was a
// vkDeviceWaitIdle on framegen's device, once per presented frame. A sync fd can be waited on
// with poll(2) instead, which parks a thread rather than draining a GPU.
//
// *out_fence_fd is set to -1 whenever a descriptor is not available: an older library, a driver
// without VK_KHR_external_fence_fd, or work that had already completed by the time it was asked
// for. -1 is not an error and the return code is still ARMSX3_LSFG_OK -- the caller must fall
// back to armsx3_lsfg_wait_idle(), which is always correct.
//
// Added in ABI 3. Resolve it with dlsym rather than assuming it: this is the one entry point a
// caller can do without.
ARMSX3_LSFG_API int armsx3_lsfg_present_fenced(int32_t ctx, int in_sem, const int* out_sems,
	uint32_t out_count, int* out_fence_fd);

ARMSX3_LSFG_API int armsx3_lsfg_destroy_context(int32_t ctx);

// Read the user's own Lossless.dll and keep the shaders it contains.
//
// Nothing is bundled or downloaded: the shaders are THS's property and the user must supply a
// legitimately purchased copy. Only the extracted SPIR-V is kept -- the DLL itself is not needed
// afterwards and the caller may delete its copy.
//
// The work is PE resource walk -> DXBC -> SPIR-V, and it is slow enough to be worth doing once
// and caching rather than at every boot. Returns the number of shaders extracted, or a negative
// armsx3_lsfg_result; armsx3_lsfg_last_error() explains a failure in terms a user can act on
// ("is Lossless Scaling up to date?" rather than a resource id).
ARMSX3_LSFG_API int armsx3_lsfg_import_shaders(const char* dll_path);

// How many shaders are currently held. Zero means frame generation cannot start.
ARMSX3_LSFG_API int armsx3_lsfg_shader_count(void);

// Serve a previously imported shader by name, for initialize()'s loader.
//
// Pass a null loader to armsx3_lsfg_initialize to use these instead of supplying your own.
ARMSX3_LSFG_API int armsx3_lsfg_get_shader(const char* name, const uint8_t** out_data, uint32_t* out_size);

// Block until framegen's device is idle.
//
// Needed on Android because framegen's device reads AHBs that OUR device writes, and there is no
// semaphore shared between the two. Without this the read races the write. It is also the reason
// frame generation cannot be free here: this is a device-level stall, not a queue wait.
ARMSX3_LSFG_API void armsx3_lsfg_wait_idle(void);

ARMSX3_LSFG_API void armsx3_lsfg_finalize(void);

// Message for the last failing call on this thread, or "" if none. Never null.
ARMSX3_LSFG_API const char* armsx3_lsfg_last_error(void);

#ifdef __cplusplus
}
#endif
