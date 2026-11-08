// SPDX-License-Identifier: GPL-2.0-only
// What the Rpcs3Bridge category files share: the includes, class extensions, queues and helpers.
#pragma once

// The declarations below carry unannotated pointers, which a header must not leave bare.
#pragma clang diagnostic ignored "-Wnullability-completeness"

// memset_s (C11 Annex K) is declared only when this is set before the first system header.
#define __STDC_WANT_LIB_EXT1__ 1

#import "Rpcs3Bridge.h"

// The pure-C contract. The implementation reaches it; Swift never does.
#include "../../bridge/rpcs3_ios_abi.h"

#include "../Support/launch_record.h"
#include "../../jit-enabler/StikJITExtensionLauncher.h"

// Plain C++ for the main-thread view calls; Swift never sees it.
#include "../../bridge/ios_frame.h"

// armsx3_ios_prepare_host, which must run before Rpcs3IOS_Initialize.
#include "../../core/render/ios_host.h"

// The façade configures and starts the session and never registers a stream controller.
#include "../Support/ios_audio_session.h"

#include "../../core/ios_stderr_witness.h"

// usleep, for the bounded stop wait. strcmp, for +audioSessionActive.
#include <string.h>
#include <unistd.h>

#include <string>
#include <vector>

@interface Rpcs3GameEntry ()
- (instancetype)initWithSerial:(NSString*)serial title:(NSString*)title category:(NSString*)category
	bootPath:(NSString*)bootPath iconPath:(nullable NSString*)iconPath backdropPath:(nullable NSString*)backdropPath
	isISO:(BOOL)isISO fromGamesYml:(BOOL)fromGamesYml;
@end

@interface Rpcs3FrameMetrics ()
- (instancetype)initWithPointsWidth:(NSInteger)pointsWidth pointsHeight:(NSInteger)pointsHeight scale:(double)scale
	pixelWidth:(NSInteger)pixelWidth pixelHeight:(NSInteger)pixelHeight displayRate:(double)displayRate;
@end

dispatch_queue_t rpcs3_bridge_queue(void);
dispatch_queue_t rpcs3_input_queue(void);
extern char s_product_config[1024];
NSString* utf8_string(const char* text);
int32_t live_int32(NSInteger value);
void append_settings_text(void* context, const char* text, uint32_t size);
