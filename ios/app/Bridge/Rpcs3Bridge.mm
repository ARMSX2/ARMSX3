// SPDX-License-Identifier: GPL-2.0-only
// The façade's implementation: marshalling only. Each area's methods are in a Rpcs3Bridge+<Area>.mm category.

#import "Rpcs3BridgeInternal.h"

#pragma clang diagnostic ignored "-Wincomplete-implementation"

// Every ABI call but the pad's runs here; serial, so the calls keep the order Swift issued them.
dispatch_queue_t rpcs3_bridge_queue(void)
{
	static dispatch_queue_t queue;
	static dispatch_once_t once;
	dispatch_once(&once, ^{
		queue = dispatch_queue_create("armsx3.rpcs3-bridge",
			dispatch_queue_attr_make_with_qos_class(DISPATCH_QUEUE_SERIAL, QOS_CLASS_USER_INITIATED, 0));
	});
	return queue;
}

// Pad, rumble, aim and save-progress calls get their own queue, so input never waits behind a boot or a stop.
dispatch_queue_t rpcs3_input_queue(void)
{
	static dispatch_queue_t queue;
	static dispatch_once_t once;
	dispatch_once(&once, ^{
		queue = dispatch_queue_create("armsx3.rpcs3-input",
			dispatch_queue_attr_make_with_qos_class(DISPATCH_QUEUE_SERIAL, QOS_CLASS_USER_INTERACTIVE, 0));
	});
	return queue;
}

// Read and written only on the façade queue, so it needs no lock. Empty until prepare has succeeded.
char s_product_config[1024];

@implementation Rpcs3GameEntry

- (instancetype)initWithSerial:(NSString*)serial title:(NSString*)title category:(NSString*)category
	bootPath:(NSString*)bootPath iconPath:(nullable NSString*)iconPath backdropPath:(nullable NSString*)backdropPath
	isISO:(BOOL)isISO fromGamesYml:(BOOL)fromGamesYml
{
	if ((self = [super init]))
	{
		_serial = [serial copy];
		_title = [title copy];
		_category = [category copy];
		_bootPath = [bootPath copy];
		_iconPath = [iconPath copy];
		_backdropPath = [backdropPath copy];
		_isISO = isISO;
		_fromGamesYml = fromGamesYml;
	}
	return self;
}

@end

@implementation Rpcs3FrameMetrics

- (instancetype)initWithPointsWidth:(NSInteger)pointsWidth pointsHeight:(NSInteger)pointsHeight scale:(double)scale
	pixelWidth:(NSInteger)pixelWidth pixelHeight:(NSInteger)pixelHeight displayRate:(double)displayRate
{
	if ((self = [super init]))
	{
		_pointsWidth = pointsWidth;
		_pointsHeight = pointsHeight;
		_scale = scale;
		_pixelWidth = pixelWidth;
		_pixelHeight = pixelHeight;
		_displayRate = displayRate;
	}
	return self;
}

@end

// nil for a null pointer or bytes that are not valid UTF-8.
NSString* utf8_string(const char* text)
{
	return text != NULL ? [NSString stringWithUTF8String:text] : nil;
}

// Written and read on main only, so it needs no lock.
static void (^s_sound_handler)(NSString*, float);

// Called on the core's RSX overlay threads, so it copies the path and posts; it never waits. No path is logged.
extern "C" void armsx3_play_sound(const char* path, float volume)
{
	if (path == NULL || path[0] == '\0')
	{
		return;
	}
	NSString* file = [[NSString alloc] initWithUTF8String:path];
	if (file == nil)
	{
		return;
	}
	dispatch_async(dispatch_get_main_queue(), ^{
		void (^handler)(NSString*, float) = s_sound_handler;
		if (handler != nil)
		{
			handler(file, volume);
		}
	});
}

// These block for minutes on a download, so a global queue, never the bridge queue a boot waits on.
static void rpcs3_bridge_ddi(int32_t op, void (^stage)(NSString* stage), NSString* __strong* result, NSString* __strong* reason)
{
	void (^forward)(const char*) = nil;
	if (stage != nil)
	{
		forward = ^(const char* word) {
			NSString* text = @(word);
			dispatch_async(dispatch_get_main_queue(), ^{
				stage(text);
			});
		};
	}
	armsx3_jit_enabler_ddi_result out;
	(void)armsx3_jit_enabler_ddi(op, forward, &out);
	out.result[sizeof out.result - 1] = '\0';
	out.reason[sizeof out.reason - 1] = '\0';
	*result = @(out.result);
	*reason = @(out.reason);
}

// INT32_MIN is outside every value set, so the ABI refuses a value that would otherwise wrap into range.
int32_t live_int32(NSInteger value)
{
	return (value < INT32_MIN || value > INT32_MAX) ? INT32_MIN : (int32_t)value;
}

// `context` is the NSMutableData the queued block below owns.
void append_settings_text(void* context, const char* text, uint32_t size)
{
	if (text != NULL)
	{
		[(__bridge NSMutableData*)context appendBytes:text length:size];
	}
}

@implementation Rpcs3Bridge

+ (void)setSoundHandler:(nullable void (^)(NSString* path, float volume))handler
{
	void (^copied)(NSString*, float) = [handler copy];
	dispatch_async(dispatch_get_main_queue(), ^{
		s_sound_handler = copied;
	});
}

+ (void)isJITAvailableWithCompletion:(void (^)(BOOL available, NSString* reason))completion
{
	dispatch_async(rpcs3_bridge_queue(), ^{
		int32_t state = 0;
		char reason[64] = {0};
		const Rpcs3IOS_Status status = Rpcs3IOS_GetJitState(&state, reason, sizeof reason);
		const BOOL on = status == RPCS3_IOS_STATUS_OK && state == RPCS3_IOS_JIT_ON;
		// A refused call reports its status number as the reason word so the caller can show it.
		NSString* word = status == RPCS3_IOS_STATUS_OK ? @(reason) : [NSString stringWithFormat:@"status-%d", (int)status];
		// The first JIT query is the first UI task, so launch_to_ui_ms is measured here, once per process, off main.
		const char* const reason_word = reason;
		static dispatch_once_t once;
		dispatch_once(&once, ^{ (void)armsx3_launch_record_write(on ? 1 : 0, reason_word); });
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(on, word);
		});
	});
}

+ (NSString*)jitHelperOutcome
{
	// The same snapshot the launch record reads; -1 means the helper never ran.
	armsx3_jit_enabler_result result;
	if (armsx3_jit_enabler_last_result(&result) != 0)
	{
		return @"";
	}
	result.helper_outcome[sizeof result.helper_outcome - 1] = '\0';
	return @(result.helper_outcome);
}

// A report field as a word: it stops at the first NUL or the field's end and keeps only [a-z-].
static NSString* rpcs3_report_word(const char* field, size_t size)
{
	NSMutableString* word = [NSMutableString string];
	for (size_t i = 0; i < size && field[i] != '\0'; i++)
	{
		const char c = field[i];
		if ((c >= 'a' && c <= 'z') || c == '-')
		{
			[word appendFormat:@"%c", c];
		}
	}
	return word;
}

+ (NSDictionary<NSString*, NSString*>*)jitPairingReport
{
	armsx3_jit_pairing_report report;
	if (armsx3_jit_enabler_pairing_report(&report) != 0)
	{
		return @{};
	}
	return @{
		@"used": rpcs3_report_word(report.used, sizeof report.used),
		@"format": rpcs3_report_word(report.format, sizeof report.format),
		@"result": rpcs3_report_word(report.result, sizeof report.result),
		@"rejected": rpcs3_report_word(report.rejected, sizeof report.rejected),
		@"documents": rpcs3_report_word(report.documents, sizeof report.documents),
		@"tried": [NSString stringWithFormat:@"%d", (int)report.tried],
	};
}

+ (void)mountDiskImageWithStage:(void (^)(NSString* stage))stage
	completion:(void (^)(NSString* result, NSString* reason))completion
{
	dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
		NSString* result = nil;
		NSString* reason = nil;
		rpcs3_bridge_ddi(ARMSX3_JIT_DDI_OP_MOUNT, stage, &result, &reason);
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(result, reason);
		});
	});
}

+ (void)resetDiskImageWithCompletion:(void (^)(NSString* result, NSString* reason))completion
{
	dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
		NSString* result = nil;
		NSString* reason = nil;
		rpcs3_bridge_ddi(ARMSX3_JIT_DDI_OP_RESET, nil, &result, &reason);
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(result, reason);
		});
	});
}

// The reads launch_record.m measures, exported rather than copied.
+ (NSDictionary<NSString*, NSNumber*>*)deviceChecks
{
	return @{
		@"getTaskAllow": @(armsx3_get_task_allow_present()),
		@"increasedMemoryLimit": @(armsx3_entitlement_present("com.apple.developer.kernel.increased-memory-limit")),
		@"extendedVirtualAddressing": @(armsx3_entitlement_present("com.apple.developer.kernel.extended-virtual-addressing")),
		@"txm": @(armsx3_txm_present()),
		@"memoryLimitBytes": @(armsx3_memory_limit_bytes()),
		@"liveContainer": @(getenv("LC_HOME_PATH") != NULL ? 1 : 0),
	};
}

+ (void)prepareWithCompletion:(void (^)(NSInteger hostErrno, NSInteger initStatus))completion
{
	dispatch_async(rpcs3_bridge_queue(), ^{
		// Host services before Initialize is the ABI's precondition. The statics are touched only on this serial queue.
		static BOOL done;
		static NSInteger host;
		static NSInteger init_status;
		if (!done)
		{
			host = armsx3_ios_prepare_host(s_product_config, sizeof s_product_config);
			init_status = host == 0 ? Rpcs3IOS_Initialize() : RPCS3_IOS_STATUS_NOT_INITIALIZED;
			done = YES;
		}
		const NSInteger host_result = host;
		const NSInteger init_result = init_status;
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(host_result, init_result);
		});
	});
}

@end
