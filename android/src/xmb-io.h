// XMB mode's players: a file the app opened as FFmpeg's input. A document from the system picker
// reaches the core as the descriptor its provider opened, passed as /proc/self/fd/N; opening that
// path again is refused for a provider's file, so FFmpeg reads the descriptor itself, through an
// AVIOContext over a copy of it. Any other path is opened as a file.

#pragma once

#include <sys/stat.h>
#include <unistd.h>

#include <cerrno>
#include <cstdint>
#include <cstdlib>
#include <string>

extern "C" {
#include <libavformat/avformat.h>
#include <libavutil/mem.h>
}

namespace xmb_io {

inline constexpr const char* kFdPrefix = "/proc/self/fd/";
inline constexpr int kBufferSize = 64 * 1024;

inline int read_fd(void* opaque, std::uint8_t* buf, int size) {
  const int fd = static_cast<int>(reinterpret_cast<std::intptr_t>(opaque));
  const ssize_t got = ::read(fd, buf, static_cast<std::size_t>(size));
  if (got < 0) return AVERROR(errno);
  return got == 0 ? AVERROR_EOF : static_cast<int>(got);
}

inline std::int64_t seek_fd(void* opaque, std::int64_t offset, int whence) {
  const int fd = static_cast<int>(reinterpret_cast<std::intptr_t>(opaque));
  if (whence & AVSEEK_SIZE) {
    struct stat st {};
    return ::fstat(fd, &st) == 0 ? static_cast<std::int64_t>(st.st_size) : AVERROR(errno);
  }
  const off_t at = ::lseek(fd, static_cast<off_t>(offset), whence & ~AVSEEK_FORCE);
  return at < 0 ? AVERROR(errno) : static_cast<std::int64_t>(at);
}

// avformat_open_input for path; *fmt is left null on failure, as avformat_open_input leaves it.
inline int open_input(AVFormatContext** fmt, const std::string& path) {
  if (path.rfind(kFdPrefix, 0) != 0) return avformat_open_input(fmt, path.c_str(), nullptr, nullptr);
  const int fd = ::dup(std::atoi(path.c_str() + std::char_traits<char>::length(kFdPrefix)));
  if (fd < 0) return AVERROR(errno);
  ::lseek(fd, 0, SEEK_SET);
  auto* buffer = static_cast<std::uint8_t*>(av_malloc(kBufferSize));
  AVIOContext* io = buffer ? avio_alloc_context(buffer, kBufferSize, 0, reinterpret_cast<void*>(static_cast<std::intptr_t>(fd)),
                                                read_fd, nullptr, seek_fd)
                           : nullptr;
  *fmt = io ? avformat_alloc_context() : nullptr;
  if (*fmt == nullptr) {
    if (io) av_freep(&io->buffer);
    else av_free(buffer);
    avio_context_free(&io);
    ::close(fd);
    return AVERROR(ENOMEM);
  }
  (*fmt)->pb = io;
  (*fmt)->flags |= AVFMT_FLAG_CUSTOM_IO;
  const int opened = avformat_open_input(fmt, nullptr, nullptr, nullptr);
  if (opened < 0) {
    // FFmpeg has freed the context but not what it was given.
    av_freep(&io->buffer);
    avio_context_free(&io);
    ::close(fd);
  }
  return opened;
}

// avformat_close_input, and the descriptor and AVIOContext open_input made for a document.
inline void close_input(AVFormatContext** fmt) {
  if (*fmt == nullptr) return;
  AVIOContext* io = ((*fmt)->flags & AVFMT_FLAG_CUSTOM_IO) ? (*fmt)->pb : nullptr;
  avformat_close_input(fmt);
  if (io != nullptr) {
    ::close(static_cast<int>(reinterpret_cast<std::intptr_t>(io->opaque)));
    av_freep(&io->buffer);
    avio_context_free(&io);
  }
}

}  // namespace xmb_io
