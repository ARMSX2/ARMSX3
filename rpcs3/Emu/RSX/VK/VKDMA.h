#pragma once
#include "vkutils/buffer_object.h"
#include "Utilities/address_range.h"

namespace vk
{
	using dma_mapping_handle = std::pair<u32, vk::buffer*>;

	dma_mapping_handle map_dma(u32 local_address, u32 length);
	void load_dma(u32 local_address, u32 length);
	void flush_dma(u32 local_address, u32 length);
	void unmap_dma(u32 local_address, u32 length);

	// ARMSX3_X_WCBPREWAIT: whether staging bytes of 'range' were reloaded from guest memory after
	// the given journal tags: by load_dma after 'load_since', or by a re-chain (a new block that
	// starts as a copy of guest memory, while copies recorded earlier write the block it replaced)
	// after 'rechain_since'. 'unknown' once the journal no longer reaches back that far.
	enum class staging_reload_check : u8
	{
		none,
		reloaded,
		unknown
	};

	staging_reload_check dma_staging_reloaded_since(const utils::address_range32& range, u64 load_since, u64 rechain_since);

	// ARMSX3_X_WCBPREWAIT: a tag ordered with the journal: a load journaled with a smaller tag had
	// finished copying guest memory before this call. Taken after a batch of readback copies is
	// recorded and before it is submitted, it separates the loads the GPU copies will land on top
	// of from those that may land on top of the GPU copies.
	u64 dma_staging_journal_mark();

	void clear_dma_resources();

	class dma_block
	{
	protected:
		struct
		{
			dma_block* parent = nullptr;
			u32 block_offset = 0;
		}
		inheritance_info;

		u32 base_address = 0;
		u8* memory_mapping = nullptr;
		std::unique_ptr<buffer> allocated_memory;

		virtual void allocate(const render_device& dev, usz size);
		virtual void free();
		virtual void* map_range(const utils::address_range32& range);
		virtual void unmap();

	public:

		dma_block() = default;
		virtual ~dma_block();

		virtual void init(const render_device& dev, u32 addr, usz size);
		virtual void init(dma_block* parent, u32 addr, usz size);
		virtual void flush(const utils::address_range32& range);
		virtual void load(const utils::address_range32& range);
		std::pair<u32, buffer*> get(const utils::address_range32& range);

		u32 start() const;
		u32 end() const;
		u32 size() const;

		dma_block* head();
		const dma_block* head() const;
		virtual void set_parent(dma_block* parent);
		virtual void extend(const render_device& dev, usz new_size);
	};

	class dma_block_EXT: public dma_block
	{
	private:
		void allocate(const render_device& dev, usz size) override;
		void* map_range(const utils::address_range32& range) override;
		void unmap() override;

	public:
		void flush(const utils::address_range32& range) override;
		void load(const utils::address_range32& range) override;
	};
}
