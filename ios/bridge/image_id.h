// SPDX-License-Identifier: GPL-2.0-only
// Load base, slide and build UUID, so a crash PC can be symbolized; none of them identifies a device or a user.
#pragma once

#include <dlfcn.h>
#include <mach-o/dyld.h>
#include <mach-o/loader.h>

#include <cstdint>
#include <cstdio>
#include <string>

namespace armsx3::bridge::diag
{
	// Empty when the image cannot be read.
	inline std::string own_image_text()
	{
		Dl_info where{};

		if (!dladdr(reinterpret_cast<const void*>(&own_image_text), &where) || !where.dli_fbase)
		{
			return {};
		}

		const auto* header = static_cast<const mach_header_64*>(where.dli_fbase);
		intptr_t slide = 0;
		bool found = false;

		for (std::uint32_t i = 0; i < _dyld_image_count(); i++)
		{
			if (_dyld_get_image_header(i) == reinterpret_cast<const mach_header*>(header))
			{
				slide = _dyld_get_image_vmaddr_slide(i);
				found = true;
				break;
			}
		}

		if (!found)
		{
			return {};
		}

		const auto* at = reinterpret_cast<const std::uint8_t*>(header + 1);

		for (std::uint32_t i = 0; i < header->ncmds; i++)
		{
			const auto* cmd = reinterpret_cast<const load_command*>(at);

			if (cmd->cmd == LC_UUID)
			{
				const auto* id = reinterpret_cast<const uuid_command*>(cmd);
				char text[160];
				int n = std::snprintf(text, sizeof text, "base=0x%llx slide=0x%llx uuid=",
					static_cast<unsigned long long>(reinterpret_cast<std::uintptr_t>(header)),
					static_cast<unsigned long long>(slide));

				for (int b = 0; b < 16; b++)
				{
					n += std::snprintf(text + n, sizeof text - n, "%s%02X", (b == 4 || b == 6 || b == 8 || b == 10) ? "-" : "", id->uuid[b]);
				}

				return text;
			}

			at += cmd->cmdsize;
		}

		return {};
	}
}
