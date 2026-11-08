// SPDX-License-Identifier: GPL-2.0-only

#import "Rpcs3BridgeInternal.h"

@implementation Rpcs3Bridge (Saves)

+ (void)saveStateToSlot:(NSInteger)fileSlot resume:(BOOL)resume
	completion:(void (^)(NSInteger status, NSString* historyName))completion
{
	dispatch_async(rpcs3_bridge_queue(), ^{
		// A negative slot wraps to a large unsigned value, which the ABI refuses as out of range.
		char name[256] = {0};
		const Rpcs3IOS_Status status = Rpcs3IOS_SaveStateToSlot((uint32_t)fileSlot, resume ? 1 : 0, name, sizeof name);
		NSString* text = (status == RPCS3_IOS_STATUS_OK ? utf8_string(name) : nil) ?: @"";
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(status, text);
		});
	});
}

+ (void)saveStateDirectoryForSerial:(NSString*)serial completion:(void (^)(NSString* path, NSInteger status))completion
{
	NSString* title = [serial copy];
	dispatch_async(rpcs3_bridge_queue(), ^{
		char dir[1024] = {0};
		const Rpcs3IOS_Status status = Rpcs3IOS_GetSaveStateDir(title.UTF8String, dir, sizeof dir);
		NSString* text = (status == RPCS3_IOS_STATUS_OK ? utf8_string(dir) : nil) ?: @"";
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(text, status);
		});
	});
}

+ (void)loadStateAtPath:(NSString*)statePath game:(Rpcs3GameEntry*)game completion:(void (^)(NSInteger status))completion
{
	NSString* state = [statePath copy];
	NSString* boot = [game.bootPath copy];
	dispatch_async(rpcs3_bridge_queue(), ^{
		// s_product_config is read here, on the façade queue, as +bootGame:completion: reads it.
		const Rpcs3IOS_Status status = Rpcs3IOS_LoadState(state.UTF8String, boot.UTF8String,
			s_product_config[0] != '\0' ? s_product_config : NULL);
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(status);
		});
	});
}

+ (void)saveStateProgressWithCompletion:(void (^)(NSInteger status, uint64_t bytes, BOOL finalizing))completion
{
	void (^done)(NSInteger, uint64_t, BOOL) = [completion copy];
	dispatch_async(rpcs3_input_queue(), ^{
		uint64_t bytes = 0;
		int32_t finalizing = 0;
		const Rpcs3IOS_Status status = Rpcs3IOS_SaveStateProgress(&bytes, &finalizing);
		dispatch_async(dispatch_get_main_queue(), ^{
			done(status, bytes, finalizing != 0);
		});
	});
}

@end
