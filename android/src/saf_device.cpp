#include "saf_device.h"

#include "Utilities/File.h"
#include "util/logs.hpp"
#include "util/types.hpp"

#include <android/log.h>
#include <unistd.h>

#include <cstdlib>
#include <memory>
#include <string>
#include <vector>

LOG_CHANNEL(saf_log, "SAF");

namespace armsx3::saf
{
	namespace
	{
		JavaVM* g_vm = nullptr;
		jclass g_class = nullptr;

		struct
		{
			jmethodID stat_path;
			jmethodID list_dir;
			jmethodID open_fd;
			jmethodID create_dir;
			jmethodID delete_path;
			jmethodID rename_path;
		} g_methods{};

		// An attachment per thread, released when the thread ends.
		//
		// fs:: calls arrive on PPU, SPU and RSX threads, none of which Java started. Attaching
		// and detaching around every call would put two JVM transitions on top of each Binder
		// round trip, so a thread attaches once and stays attached.
		class scoped_env
		{
			JNIEnv* m_env = nullptr;

		public:
			scoped_env()
			{
				if (!g_vm)
				{
					return;
				}

				if (g_vm->GetEnv(reinterpret_cast<void**>(&m_env), JNI_VERSION_1_6) == JNI_OK)
				{
					return;
				}

				struct detacher
				{
					~detacher()
					{
						if (attached && g_vm)
						{
							g_vm->DetachCurrentThread();
						}
					}

					bool attached = false;
				};

				thread_local detacher tls{};

				// As a daemon, so a thread still attached at shutdown cannot hold the VM open.
				if (g_vm->AttachCurrentThreadAsDaemon(&m_env, nullptr) == JNI_OK)
				{
					tls.attached = true;
				}
				else
				{
					m_env = nullptr;
				}
			}

			explicit operator bool() const
			{
				return m_env != nullptr && g_class != nullptr;
			}

			JNIEnv* operator->() const
			{
				return m_env;
			}

			JNIEnv* get() const
			{
				return m_env;
			}

			// Java exceptions do not unwind C++, they stay pending and poison the next call.
			void clear_pending() const
			{
				if (m_env && m_env->ExceptionCheck())
				{
					m_env->ExceptionDescribe();
					m_env->ExceptionClear();
				}
			}
		};

		// RAII for a local ref, since a long boot makes thousands of these and the local frame
		// on an attached thread is not popped for us.
		template <typename T>
		class local_ref
		{
			JNIEnv* m_env;
			T m_ref;

		public:
			local_ref(JNIEnv* env, T ref)
				: m_env(env), m_ref(ref)
			{
			}

			~local_ref()
			{
				if (m_ref)
				{
					m_env->DeleteLocalRef(m_ref);
				}
			}

			local_ref(const local_ref&) = delete;
			local_ref& operator=(const local_ref&) = delete;

			T get() const
			{
				return m_ref;
			}

			explicit operator bool() const
			{
				return m_ref != nullptr;
			}
		};

		std::string to_utf8(JNIEnv* env, jstring value)
		{
			if (!value)
			{
				return {};
			}

			const char* chars = env->GetStringUTFChars(value, nullptr);
			std::string result = chars ? chars : "";
			if (chars)
			{
				env->ReleaseStringUTFChars(value, chars);
			}
			return result;
		}

		// A node as Kotlin reported it. kind < 0 means there is nothing at that path.
		struct node
		{
			s64 kind = -1;
			u64 size = 0;
			s64 mtime = 0;

			bool exists() const
			{
				return kind >= 0;
			}

			bool is_dir() const
			{
				return kind == 1;
			}
		};

		node query(const std::string& path)
		{
			scoped_env env;
			if (!env)
			{
				return {};
			}

			local_ref<jstring> arg(env.get(), env->NewStringUTF(path.c_str()));
			if (!arg)
			{
				env.clear_pending();
				return {};
			}

			local_ref<jlongArray> reply(env.get(), static_cast<jlongArray>(
				env->CallStaticObjectMethod(g_class, g_methods.stat_path, arg.get())));
			env.clear_pending();

			if (!reply || env->GetArrayLength(reply.get()) < 3)
			{
				return {};
			}

			jlong values[3]{};
			env->GetLongArrayRegion(reply.get(), 0, 3, values);

			node result{};
			result.kind = values[0];
			result.size = static_cast<u64>(values[1]);
			result.mtime = values[2];
			return result;
		}

		bool call_bool(jmethodID method, const std::string& path)
		{
			scoped_env env;
			if (!env)
			{
				return false;
			}

			local_ref<jstring> arg(env.get(), env->NewStringUTF(path.c_str()));
			if (!arg)
			{
				env.clear_pending();
				return false;
			}

			const jboolean ok = env->CallStaticBooleanMethod(g_class, method, arg.get());
			env.clear_pending();
			return ok == JNI_TRUE;
		}

		void fill_stat(const node& from, fs::stat_t& info)
		{
			info = fs::stat_t{
				.is_directory = from.is_dir(),
				.is_symlink = false,
				.is_writable = true,
				.size = from.size,
				.atime = from.mtime,
				.mtime = from.mtime,
				.ctime = from.mtime,
			};
		}

		// Children are read in one query and held, because fs::dir::read is called in a loop
		// and a cursor cannot be kept open across the JNI boundary safely.
		class saf_dir final : public fs::dir_base
		{
			std::vector<fs::dir_entry> m_entries;
			usz m_pos = 0;

		public:
			explicit saf_dir(std::vector<fs::dir_entry> entries)
				: m_entries(std::move(entries))
			{
			}

			bool read(fs::dir_entry& out) override
			{
				if (m_pos >= m_entries.size())
				{
					return false;
				}

				out = m_entries[m_pos++];
				return true;
			}

			void rewind() override
			{
				m_pos = 0;
			}
		};

		// "F\t<size>\t<mtime>\t<name>", name last so a tab inside it cannot shift the fields.
		bool parse_entry(const std::string& record, fs::dir_entry& out)
		{
			const usz a = record.find('\t');
			if (a == umax)
			{
				return false;
			}

			const usz b = record.find('\t', a + 1);
			if (b == umax)
			{
				return false;
			}

			const usz c = record.find('\t', b + 1);
			if (c == umax)
			{
				return false;
			}

			out = fs::dir_entry{};
			out.name = record.substr(c + 1);
			if (out.name.empty())
			{
				return false;
			}

			out.is_directory = record[0] == 'D';
			out.is_symlink = false;
			out.is_writable = true;
			out.size = std::strtoull(record.c_str() + a + 1, nullptr, 10);
			out.mtime = std::strtoll(record.c_str() + b + 1, nullptr, 10);
			out.atime = out.mtime;
			out.ctime = out.mtime;
			return true;
		}

		class saf_device final : public fs::device_base
		{
		public:
			saf_device()
			{
				fs_prefix = device_prefix;
			}

			bool stat(const std::string& path, fs::stat_t& info) override
			{
				const node found = query(relative(path));

				if (!found.exists())
				{
					fs::g_tls_error = fs::error::noent;
					return false;
				}

				fill_stat(found, info);
				return true;
			}

			// There is no way to ask a storage provider how much room is left on the volume it
			// is backed by, and the honest answer for a tree URI is that it does not have one.
			// Reporting a large free space keeps the "not enough space" checks from refusing
			// work that would in fact succeed.
			bool statfs(const std::string& path, fs::device_stat& info) override
			{
				if (!query(relative(path)).exists())
				{
					fs::g_tls_error = fs::error::noent;
					return false;
				}

				constexpr u64 headroom = 64ull << 30;

				info = fs::device_stat{
					.block_size = 4096,
					.total_size = headroom,
					.total_free = headroom,
					.avail_free = headroom,
				};

				return true;
			}

			std::unique_ptr<fs::file_base> open(const std::string& path, bs_t<fs::open_mode> mode) override
			{
				const std::string rel = relative(path);
				const node found = query(rel);

				if (found.exists() && found.is_dir())
				{
					fs::g_tls_error = fs::error::isdir;
					return nullptr;
				}

				if (found.exists() && (mode & fs::excl) && (mode & fs::create))
				{
					fs::g_tls_error = fs::error::exist;
					return nullptr;
				}

				if (!found.exists() && !(mode & fs::create))
				{
					fs::g_tls_error = fs::error::noent;
					return nullptr;
				}

				const int fd = open_fd(rel, mode_string(mode, found.exists()), !!(mode & fs::create));

				if (fd < 0)
				{
					fs::g_tls_error = fs::error::noent;
					return nullptr;
				}

				// Hand the descriptor to the core's own unix file wrapper rather than writing a
				// second one. It already retries EINTR, which matters more here than anywhere:
				// a document provider is FUSE-backed, so short reads and interrupted syscalls
				// are ordinary rather than exceptional.
				return fs::file::from_native_handle(fd).release();
			}

			std::unique_ptr<fs::dir_base> open_dir(const std::string& path) override
			{
				const std::string rel = relative(path);
				const node found = query(rel);

				if (!found.exists())
				{
					fs::g_tls_error = fs::error::noent;
					return nullptr;
				}

				if (!found.is_dir())
				{
					fs::g_tls_error = fs::error::notdir;
					return nullptr;
				}

				return std::make_unique<saf_dir>(list(rel));
			}

			bool create_dir(const std::string& path) override
			{
				if (!call_bool(g_methods.create_dir, relative(path)))
				{
					fs::g_tls_error = fs::error::exist;
					return false;
				}

				return true;
			}

			bool remove_dir(const std::string& path) override
			{
				return remove(path);
			}

			bool remove(const std::string& path) override
			{
				if (!call_bool(g_methods.delete_path, relative(path)))
				{
					fs::g_tls_error = fs::error::noent;
					return false;
				}

				return true;
			}

			bool rename(const std::string& from, const std::string& to) override
			{
				scoped_env env;
				if (!env)
				{
					fs::g_tls_error = fs::error::noent;
					return false;
				}

				const std::string from_rel = relative(from);
				const std::string to_rel = relative(to);

				local_ref<jstring> a(env.get(), env->NewStringUTF(from_rel.c_str()));
				local_ref<jstring> b(env.get(), env->NewStringUTF(to_rel.c_str()));

				if (!a || !b)
				{
					env.clear_pending();
					fs::g_tls_error = fs::error::noent;
					return false;
				}

				const jboolean ok = env->CallStaticBooleanMethod(
					g_class, g_methods.rename_path, a.get(), b.get());
				env.clear_pending();

				if (ok != JNI_TRUE)
				{
					fs::g_tls_error = fs::error::noent;
					return false;
				}

				return true;
			}

			// No SAF call truncates a document, but a descriptor opened on one is an ordinary
			// file descriptor once it exists, so do it through that.
			bool trunc(const std::string& path, u64 length) override
			{
				const std::string rel = relative(path);

				if (!query(rel).exists())
				{
					fs::g_tls_error = fs::error::noent;
					return false;
				}

				const int fd = open_fd(rel, "rw", false);

				if (fd < 0)
				{
					fs::g_tls_error = fs::error::noent;
					return false;
				}

				const bool ok = ::ftruncate(fd, static_cast<off_t>(length)) == 0;
				::close(fd);

				if (!ok)
				{
					fs::g_tls_error = fs::error::inval;
				}

				return ok;
			}

			// A document provider owns its timestamps and offers no way to set them. Reporting
			// failure would turn every copy the core makes into an error it cannot act on, so
			// the times are simply left as the provider recorded them.
			bool utime(const std::string&, s64, s64) override
			{
				return true;
			}

		private:
			std::string relative(const std::string& path) const
			{
				std::string_view view = path;

				if (view.starts_with(fs_prefix))
				{
					view.remove_prefix(fs_prefix.size());
				}

				while (!view.empty() && view.front() == '/')
				{
					view.remove_prefix(1);
				}

				while (!view.empty() && view.back() == '/')
				{
					view.remove_suffix(1);
				}

				return std::string(view);
			}

			static const char* mode_string(bs_t<fs::open_mode> mode, bool exists)
			{
				const bool wants_read = !!(mode & fs::read);
				const bool wants_write = !!(mode & fs::write);

				// Truncation is requested on the open itself, and asking for it on a file that
				// is about to be created is not something every provider tolerates.
				const bool truncate = !!(mode & fs::trunc) && exists;

				if (wants_write && wants_read)
				{
					return truncate ? "rwt" : "rw";
				}

				if (wants_write)
				{
					if (mode & fs::append)
					{
						return "wa";
					}

					return truncate ? "wt" : "w";
				}

				return "r";
			}

			static int open_fd(const std::string& rel, const char* mode, bool create)
			{
				scoped_env env;
				if (!env)
				{
					return -1;
				}

				local_ref<jstring> path(env.get(), env->NewStringUTF(rel.c_str()));
				local_ref<jstring> flags(env.get(), env->NewStringUTF(mode));

				if (!path || !flags)
				{
					env.clear_pending();
					return -1;
				}

				const jint fd = env->CallStaticIntMethod(
					g_class, g_methods.open_fd, path.get(), flags.get(),
					create ? JNI_TRUE : JNI_FALSE);
				env.clear_pending();

				return fd;
			}

			static std::vector<fs::dir_entry> list(const std::string& rel)
			{
				std::vector<fs::dir_entry> result;

				scoped_env env;
				if (!env)
				{
					return result;
				}

				local_ref<jstring> arg(env.get(), env->NewStringUTF(rel.c_str()));
				if (!arg)
				{
					env.clear_pending();
					return result;
				}

				local_ref<jobjectArray> reply(env.get(), static_cast<jobjectArray>(
					env->CallStaticObjectMethod(g_class, g_methods.list_dir, arg.get())));
				env.clear_pending();

				if (!reply)
				{
					return result;
				}

				const jsize count = env->GetArrayLength(reply.get());
				result.reserve(count);

				for (jsize i = 0; i < count; i++)
				{
					local_ref<jstring> item(env.get(), static_cast<jstring>(
						env->GetObjectArrayElement(reply.get(), i)));

					if (!item)
					{
						continue;
					}

					fs::dir_entry entry{};
					if (parse_entry(to_utf8(env.get(), item.get()), entry))
					{
						result.push_back(std::move(entry));
					}
				}

				return result;
			}
		};
	}

	bool is_device_path(std::string_view path)
	{
		return path.starts_with(device_prefix);
	}

	void install(JNIEnv* env)
	{
		if (!env || g_class)
		{
			return;
		}

		if (env->GetJavaVM(&g_vm) != JNI_OK || !g_vm)
		{
			return;
		}

		jclass found = env->FindClass("com/armsx2/storage/ContentUri");

		if (!found || env->ExceptionCheck())
		{
			env->ExceptionClear();
			saf_log.error("ContentUri is missing, picked folders will not be readable");
			return;
		}

		jclass global = static_cast<jclass>(env->NewGlobalRef(found));
		env->DeleteLocalRef(found);

		if (!global)
		{
			return;
		}

		g_methods.stat_path = env->GetStaticMethodID(global, "statPath", "(Ljava/lang/String;)[J");
		g_methods.list_dir = env->GetStaticMethodID(global, "listDir", "(Ljava/lang/String;)[Ljava/lang/String;");
		g_methods.open_fd = env->GetStaticMethodID(global, "openFd", "(Ljava/lang/String;Ljava/lang/String;Z)I");
		g_methods.create_dir = env->GetStaticMethodID(global, "createDir", "(Ljava/lang/String;)Z");
		g_methods.delete_path = env->GetStaticMethodID(global, "deletePath", "(Ljava/lang/String;)Z");
		g_methods.rename_path = env->GetStaticMethodID(global, "renamePath", "(Ljava/lang/String;Ljava/lang/String;)Z");

		if (env->ExceptionCheck() || !g_methods.stat_path || !g_methods.list_dir ||
			!g_methods.open_fd || !g_methods.create_dir || !g_methods.delete_path ||
			!g_methods.rename_path)
		{
			env->ExceptionClear();
			env->DeleteGlobalRef(global);
			saf_log.error("ContentUri does not have the expected methods");
			return;
		}

		g_class = global;

		fs::set_virtual_device(device_name, stx::make_shared<saf_device>());
		saf_log.success("Storage bridge ready, picked folders are readable");
	}
}
