#pragma once

#include "../VulkanAPI.h"
#include "Utilities/mutex.h"

#include "commands.h"
#include "device.h"

#include "Emu/RSX/Common/simple_array.hpp"

namespace vk
{
	struct gc_callback_t
	{
		std::function<void()> m_callback;

		gc_callback_t(std::function<void()> callback)
			: m_callback(callback)
		{}

		~gc_callback_t()
		{
			if (m_callback)
			{
				m_callback();
			}
		}
	};

	struct descriptor_set_dynamic_offset_t
	{
		int location;
		u32 value;
	};

	class descriptor_pool
	{
	public:
		descriptor_pool() = default;
		~descriptor_pool() = default;

		void create(const vk::render_device& dev, const rsx::simple_array<VkDescriptorPoolSize>& pool_sizes, u32 min_sets = 1024, u32 max_sets = 1024);
		void destroy();

		VkDescriptorSet allocate(VkDescriptorSetLayout layout, VkBool32 use_cache = VK_TRUE);

		operator VkDescriptorPool() { return m_current_pool_handle; }
		FORCE_INLINE bool valid() const { return !m_device_subpools.empty(); }
		FORCE_INLINE u32 max_sets() const { return m_current_subpool_index >= m_device_subpools.size() ? 0u : m_device_subpools[m_current_subpool_index].size; }

	private:
		FORCE_INLINE bool can_allocate(u32 required_count, u32 already_used_count = 0) const { return (required_count + already_used_count) <= max_sets(); };
		void reset(u32 subpool_id, VkDescriptorPoolResetFlags flags);
		void next_subpool();

		std::pair<VkResult, VkDescriptorPool> new_subpool();

		struct logical_subpool_t
		{
			VkDescriptorPool handle = VK_NULL_HANDLE;
			u32 size = 0;
			VkBool32 busy = VK_FALSE;
		};

		struct autoscaling_config_t
		{
			u32 min_pool_size = 0;
			u32 max_pool_size = 0;
			u32 current_size = 0;

			// Debounce setup.
			static constexpr u32 increment_min_steps = 2u;
			u32 increment_steps = 0;

			u32 get_pool_size();
		};

		const vk::render_device* m_owner = nullptr;
		VkDescriptorPoolCreateInfo m_create_info = {};
		autoscaling_config_t m_autoscaling_config = {};
		rsx::simple_array<VkDescriptorPoolSize> m_create_info_pool_sizes;

		rsx::simple_array<logical_subpool_t> m_device_subpools;
		VkDescriptorPool m_current_pool_handle = VK_NULL_HANDLE;
		u32 m_current_subpool_index = umax;
		u32 m_current_subpool_offset = 0;

		shared_mutex m_subpool_lock;

		static constexpr size_t max_cache_size = 64;
		VkDescriptorSetLayout m_cached_layout = VK_NULL_HANDLE;
		rsx::simple_array<VkDescriptorSet> m_descriptor_set_cache;
		rsx::simple_array<VkDescriptorSetLayout> m_allocation_request_cache;
	};

	class descriptor_set
	{
		// How many writes may queue before on_bind() forces a flush, and -- through
		// m_pool_size -- how much every descriptor_set reserves the first time it is used.
		//
		// The reserve is not an optimisation. push_*() hands Vulkan the address of a pool
		// entry (&m_buffer_info_pool.back()) and that address has to stay valid until
		// flush(), so the pools are sized up front to a capacity the queue cannot exceed
		// and never reallocated while writes are pending. simple_array::clear() only resets
		// the size, so the memory is held for the object's whole life.
		//
		// At 16384 that is 16448 entries in each of three pools -- roughly 920KB per
		// descriptor_set, and there is one per shader program. On a desktop that is
		// invisible. On a phone it is the largest thing that grows during play: a heap
		// profile of Ratchet & Clank on an Adreno 740 caught 56 of them created in half a
		// session, 49MB, and it climbs for as long as new pipelines keep appearing. It is
		// plain malloc rather than device memory, so it never shows up in the VMM's numbers
		// and no amount of texture eviction touches it -- the pool read 516MB while the
		// process sat at 5.6GB and was killed.
		//
		// 1024 costs one extra vkUpdateDescriptorSets per 1024 writes and takes the
		// reservation to ~57KB. The two constants have to move together: the flush
		// threshold is what guarantees the queue never outruns the reservation.
#ifdef __ANDROID__
		static constexpr size_t max_cache_size = 1024;
#else
		static constexpr size_t max_cache_size = 16384;
#endif
		static constexpr size_t max_overflow_size = 64;
		static constexpr size_t m_pool_size = max_cache_size + max_overflow_size;

		void init(VkDescriptorSet new_set);

	public:
#if defined(__clang__) && (__clang_major__ < 16)
		// Clang (pre 16.x) does not support LWG 2089, std::construct_at for POD types
		struct WriteDescriptorSetT : public VkWriteDescriptorSet
		{
			WriteDescriptorSetT(
				VkStructureType                  sType,
				const void*                      pNext,
				VkDescriptorSet                  dstSet,
				uint32_t                         dstBinding,
				uint32_t                         dstArrayElement,
				uint32_t                         descriptorCount,
				VkDescriptorType                 descriptorType,
				const VkDescriptorImageInfo*     pImageInfo,
				const VkDescriptorBufferInfo*    pBufferInfo,
				const VkBufferView*              pTexelBufferView)
			{
				this->sType = sType,
				this->pNext = pNext,
				this->dstSet = dstSet,
				this->dstBinding = dstBinding,
				this->dstArrayElement = dstArrayElement,
				this->descriptorCount = descriptorCount,
				this->descriptorType = descriptorType,
				this->pImageInfo = pImageInfo,
				this->pBufferInfo = pBufferInfo,
				this->pTexelBufferView = pTexelBufferView;
			}
		};
#else
		using WriteDescriptorSetT = VkWriteDescriptorSet;
#endif

	public:
		descriptor_set(VkDescriptorSet set);
		descriptor_set() = default;
		~descriptor_set();

		descriptor_set(const descriptor_set&) = delete;

		void swap(descriptor_set& other);
		descriptor_set& operator = (VkDescriptorSet set);

		VkDescriptorSet value() const { return m_handle; }
		operator bool() const { return m_handle != VK_NULL_HANDLE; }

		VkDescriptorSet* ptr();
		void push(const VkBufferView& buffer_view, VkDescriptorType type, u32 binding);
		void push(const VkDescriptorBufferInfo& buffer_info, VkDescriptorType type, u32 binding);
		void push(const VkDescriptorImageInfo& image_info, VkDescriptorType type, u32 binding);
		void push(const VkDescriptorImageInfo* image_info, u32 count, VkDescriptorType type, u32 binding);
		void push(const rsx::simple_array<VkCopyDescriptorSet>& copy_cmd, u32 type_mask = umax);
		void push(const rsx::simple_array<VkWriteDescriptorSet>& write_cmds, u32 type_mask = umax);
		void push(const descriptor_set_dynamic_offset_t& offset);

		// Event handlers
		void on_bind();
		void bind(const vk::command_buffer& cmd, VkPipelineBindPoint bind_point, VkPipelineLayout layout);

		void flush();

		// Typed temporary storage access. Should be inline, the overhead is significant
		FORCE_INLINE VkBufferView* store(const VkBufferView& buffer_view)
		{
			m_buffer_view_pool.push_back(buffer_view);
			return &m_buffer_view_pool.back();
		}

		FORCE_INLINE VkDescriptorBufferInfo* store(const VkDescriptorBufferInfo& buffer_info)
		{
			m_buffer_info_pool.push_back(buffer_info);
			return &m_buffer_info_pool.back();
		}

		FORCE_INLINE VkDescriptorImageInfo* store(const VkDescriptorImageInfo& image_info)
		{
			m_image_info_pool.push_back(image_info);
			return &m_image_info_pool.back();
		}

		FORCE_INLINE VkDescriptorImageInfo* store(const rsx::simple_array<VkDescriptorImageInfo>& image_infos)
		{
			const auto offset = m_image_info_pool.size();
			m_image_info_pool += image_infos;
			return &m_image_info_pool[offset];
		}

		FORCE_INLINE bool storage_cache_pressure() const
		{
			return
				m_image_info_pool.size() >= max_cache_size ||
				m_buffer_info_pool.size() >= max_cache_size ||
				m_buffer_view_pool.size() >= max_cache_size;
		}

		// Temporary storage accessors
		const rsx::simple_array<WriteDescriptorSetT> peek() const { return m_pending_writes; }
		u64 cache_id() const { return m_storage_cache_id; }

		// Basic lockable for storage coherence
		void lock() { m_storage_lock.lock(); }
		void unlock() { m_storage_lock.unlock(); }

	private:
		VkDescriptorSet m_handle = VK_NULL_HANDLE;
		u64 m_update_after_bind_mask = 0;
		u64 m_push_type_mask = 0;
		bool m_in_use = false;

		shared_mutex m_storage_lock;
		atomic_t<u64> m_storage_cache_id = 0;

		rsx::simple_array<VkBufferView> m_buffer_view_pool;
		rsx::simple_array<VkDescriptorBufferInfo> m_buffer_info_pool;
		rsx::simple_array<VkDescriptorImageInfo> m_image_info_pool;
		rsx::simple_array<u32> m_dynamic_offsets;

		rsx::simple_array<WriteDescriptorSetT> m_pending_writes;
		rsx::simple_array<VkCopyDescriptorSet> m_pending_copies;
	};

	namespace descriptors
	{
		void init();
		void flush();
		void destroy();

		VkDescriptorSetLayout create_layout(const rsx::simple_array<VkDescriptorSetLayoutBinding>& bindings);
	}
}
