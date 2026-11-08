// SPDX-License-Identifier: GPL-2.0-only

#import "Rpcs3BridgeInternal.h"

@implementation Rpcs3Bridge (Diagnostics)

+ (void)startDiagnosticsInDirectory:(NSString*)directory verbose:(BOOL)verbose
	completion:(void (^)(NSInteger status))completion
{
	NSString* folder = [directory copy];
	dispatch_async(rpcs3_bridge_queue(), ^{
		const Rpcs3IOS_Status status = Rpcs3IOS_DiagStart(folder.fileSystemRepresentation, verbose ? 1 : 0);
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(status);
		});
	});
}

+ (void)syncLogWithCompletion:(void (^)(NSInteger status))completion
{
	dispatch_async(rpcs3_bridge_queue(), ^{
		const Rpcs3IOS_Status status = Rpcs3IOS_DiagSync();
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(status);
		});
	});
}

+ (void)setVerboseLogging:(BOOL)on completion:(void (^)(NSInteger status))completion
{
	dispatch_async(rpcs3_bridge_queue(), ^{
		const Rpcs3IOS_Status status = Rpcs3IOS_DiagSetVerbose(on ? 1 : 0);
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(status);
		});
	});
}

+ (void)versionWithCompletion:(void (^)(NSString* version, NSInteger status))completion
{
	dispatch_async(rpcs3_bridge_queue(), ^{
		char version[512] = {0};
		const Rpcs3IOS_Status status = Rpcs3IOS_GetVersion(version, sizeof version);
		NSString* text = (status == RPCS3_IOS_STATUS_OK ? utf8_string(version) : nil) ?: @"";
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(text, status);
		});
	});
}

+ (void)configDirectoryWithCompletion:(void (^)(NSString* path, NSInteger status))completion
{
	dispatch_async(rpcs3_bridge_queue(), ^{
		char dir[1024] = {0};
		const Rpcs3IOS_Status status = Rpcs3IOS_GetConfigDir(dir, sizeof dir);
		NSString* text = (status == RPCS3_IOS_STATUS_OK ? utf8_string(dir) : nil) ?: @"";
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(text, status);
		});
	});
}

@end
