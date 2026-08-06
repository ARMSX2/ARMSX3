ARMSX3
======

Proof of concept Android port of RPCS3.

This is early work. A game boots and plays, but it is slow and most of it is
untested. It is not a usable emulator yet.

Status
------

Skate 3 boots, loads and reaches gameplay at roughly 20 to 30 fps on a
Snapdragon 8 Gen 2. Rendering, audio, touch controls and physical controllers
work. Almost nothing else has been tested.

Differences from upstream RPCS3
-------------------------------

Some of the fixes here are not in upstream and affect any ARM64 build, not only
Android:

* Shaders declared runtime sized arrays inside uniform blocks, which requires
  VK_EXT_shader_uniform_buffer_unsized_array. Adreno does not support that
  extension, so every game pipeline failed to compile and nothing rendered.
  Concrete array bounds are emitted when the extension is missing.

* The ARM64 SPU block verification checksum folded two thirds of every block
  through an absolute difference. That collides on the near identical job
  binaries an SPU job manager streams through the same local store address, so
  a cached block could end up running against another job's code. It sums now.

* Thread affinity was compiled out on Android, and the core had no ARM
  big.LITTLE topology, so SPU and RSX threads were never placed on the fast
  cores.

* The LLVM JIT target was pinned to cortex-a34, an in order core from 2016. It
  detects the host now.

Building
--------

See BUILDING.md.

The Discord Social SDK is proprietary and is not redistributed here. Get it from
Discord's developer portal if you want that feature.

License
-------

GPL-2.0-only, the same as RPCS3. See LICENSE. Some files may be licensed
differently, check the file headers.

Based on RPCS3, https://github.com/RPCS3/rpcs3
