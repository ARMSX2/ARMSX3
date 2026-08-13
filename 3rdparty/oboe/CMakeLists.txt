# Oboe
#
# Android-only. Oboe wraps AAudio (and OpenSL ES on older devices) and carries a
# per-device quirks database plus stream-restart handling, which is the part that
# matters on the low-end parts where plain AAudio glitches.

add_subdirectory(oboe EXCLUDE_FROM_ALL)
add_library(3rdparty::oboe ALIAS oboe)
