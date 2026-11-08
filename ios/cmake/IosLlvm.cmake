# SPDX-License-Identifier: GPL-2.0-only
# LLVM for arm64-ios: point LLVM_DIR at the cross-built install so upstream's prebuilt-LLVM branch finds it; include before add_subdirectory(3rdparty).

# Inert unless WITH_LLVM, so ios-nollvm configures with no LLVM install on disk.
if(WITH_LLVM)

# Derived from the source tree so a checkout anywhere resolves; not FORCEd, since a caller may point it elsewhere.
set(ARMSX3_IOS_LLVM_PREFIX "${CMAKE_CURRENT_SOURCE_DIR}/ios/build-deps/llvm-ios-install"
    CACHE PATH "ARMSX3 iOS: the install prefix ios/scripts/build-llvm-ios.sh writes.")

set(ARMSX3_IOS_LLVM_CMAKE_DIR "${ARMSX3_IOS_LLVM_PREFIX}/lib/cmake/llvm")

if(NOT EXISTS "${ARMSX3_IOS_LLVM_CMAKE_DIR}/LLVMConfig.cmake")
    message(FATAL_ERROR
        "ARMSX3: the arm64-ios LLVM package is missing:\n"
        "  ${ARMSX3_IOS_LLVM_CMAKE_DIR}/LLVMConfig.cmake\n"
        "Run ios/scripts/build-llvm-ios.sh to cross-build it from the SHA-pinned "
        "3rdparty/llvm/llvm submodule (a host llvm-tblgen bootstrap, then an AArch64-only "
        "arm64-ios build). The configure is stopped here deliberately: without LLVM_DIR named, "
        "3rdparty/llvm/CMakeLists.txt's find_package(LLVM CONFIG) searches CMAKE_PREFIX_PATH and "
        "the HOST, and a macOS Homebrew or Xcode LLVM found there configures cleanly and then "
        "fails at link with an architecture mismatch.\n"
        "To build the iOS tree without LLVM at all, use ios/configure.sh ios-nollvm.")
endif()

# FORCE, because a plain set() would be ignored on a reconfigure and revert to a host answer.
set(LLVM_DIR "${ARMSX3_IOS_LLVM_CMAKE_DIR}" CACHE PATH
    "ARMSX3 iOS: LLVMConfig.cmake from the cross-built AArch64-only arm64-ios install." FORCE)

# Report the version, because upstream's find_package only prints that it used a prebuilt LLVM.
file(STRINGS "${ARMSX3_IOS_LLVM_CMAKE_DIR}/LLVMConfig.cmake" ARMSX3_IOS_LLVM_VERSION_LINE
     REGEX "^set\\(LLVM_PACKAGE_VERSION " LIMIT_COUNT 1)
string(REGEX REPLACE "^set\\(LLVM_PACKAGE_VERSION \"?([^\")]+)\"?\\).*$" "\\1"
       ARMSX3_IOS_LLVM_VERSION "${ARMSX3_IOS_LLVM_VERSION_LINE}")

message(STATUS "ARMSX3: LLVM ${ARMSX3_IOS_LLVM_VERSION} -> cross-built arm64-ios install "
               "${ARMSX3_IOS_LLVM_PREFIX} (targets AArch64, static)")

endif()
