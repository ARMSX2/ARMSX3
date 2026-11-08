// SPDX-License-Identifier: GPL-2.0-only

#import "Rpcs3BridgeInternal.h"

// `context` is the caller's progress block, kept alive by the queued call; each report hops to main.
static void firmware_progress(void* context, uint32_t done, uint32_t total)
{
	void (^progress)(NSUInteger, NSUInteger) = (__bridge void (^)(NSUInteger, NSUInteger))context;
	dispatch_async(dispatch_get_main_queue(), ^{
		progress(done, total);
	});
}

// `context` is the caller's progress block, kept alive by the queued call; each report hops to main.
static void package_progress(void* context, uint32_t file_index, uint32_t permille)
{
	void (^progress)(NSUInteger, NSUInteger) = (__bridge void (^)(NSUInteger, NSUInteger))context;
	dispatch_async(dispatch_get_main_queue(), ^{
		progress(file_index, permille);
	});
}

@implementation Rpcs3Bridge (Install)

+ (void)installFirmwareAtPath:(NSString*)path
	progress:(nullable void (^)(NSUInteger done, NSUInteger total))progress
	completion:(void (^)(NSInteger status))completion
{
	NSString* pup = [path copy];
	void (^report)(NSUInteger, NSUInteger) = [progress copy];
	dispatch_async(rpcs3_bridge_queue(), ^{
		const Rpcs3IOS_Status status = Rpcs3IOS_InstallFirmware(pup.UTF8String,
			report != nil ? firmware_progress : NULL, (__bridge void*)report);
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(status);
		});
	});
}

+ (void)installPackagesAtPaths:(NSArray<NSString*>*)paths
	progress:(nullable void (^)(NSUInteger fileIndex, NSUInteger permille))progress
	completion:(void (^)(NSInteger status, NSString* titleId))completion
{
	NSArray<NSString*>* files = [paths copy];
	void (^report)(NSUInteger, NSUInteger) = [progress copy];
	dispatch_async(rpcs3_bridge_queue(), ^{
		// The C strings point into `files`, which this block keeps alive until the call returns.
		std::vector<const char*> list;
		list.reserve(files.count);
		for (NSString* file in files)
		{
			list.push_back(file.UTF8String);
		}
		char title[32] = {0};
		const Rpcs3IOS_Status status = Rpcs3IOS_InstallPkg(list.data(), (uint32_t)list.size(),
			report != nil ? package_progress : NULL, (__bridge void*)report, title, sizeof title);
		NSString* text = (status == RPCS3_IOS_STATUS_OK ? utf8_string(title) : nil) ?: @"";
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(status, text);
		});
	});
}

+ (void)installLicenceAtPath:(NSString*)path forSerial:(nullable NSString*)serial
	completion:(void (^)(NSInteger status))completion
{
	NSString* file = [path copy];
	NSString* game = [serial copy];
	dispatch_async(rpcs3_bridge_queue(), ^{
		const Rpcs3IOS_Status status = Rpcs3IOS_InstallLicence(file.UTF8String, game.UTF8String);
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(status);
		});
	});
}

+ (void)probeLicenceForSerial:(NSString*)serial
	completion:(void (^)(NSInteger locked, NSString* contentId, NSInteger status))completion
{
	NSString* game = [serial copy];
	dispatch_async(rpcs3_bridge_queue(), ^{
		int32_t locked = 0;
		char content_id[64] = {0};
		const Rpcs3IOS_Status status = Rpcs3IOS_ProbeLicence(game.UTF8String, &locked, content_id, sizeof content_id);
		NSString* text = (status == RPCS3_IOS_STATUS_OK ? utf8_string(content_id) : nil) ?: @"";
		const NSInteger answer = status == RPCS3_IOS_STATUS_OK ? locked : 0;
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(answer, text, status);
		});
	});
}

+ (void)firmwareVersionWithCompletion:(void (^)(NSString* version, NSInteger status))completion
{
	dispatch_async(rpcs3_bridge_queue(), ^{
		char version[64] = {0};
		const Rpcs3IOS_Status status = Rpcs3IOS_GetFirmwareVersion(version, sizeof version);
		NSString* text = (status == RPCS3_IOS_STATUS_OK ? utf8_string(version) : nil) ?: @"";
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(text, status);
		});
	});
}

+ (void)userDirectoryWithCompletion:(void (^)(NSString* path, NSInteger status))completion
{
	dispatch_async(rpcs3_bridge_queue(), ^{
		char dir[1024] = {0};
		const Rpcs3IOS_Status status = Rpcs3IOS_GetUserDir(dir, sizeof dir);
		NSString* text = (status == RPCS3_IOS_STATUS_OK ? utf8_string(dir) : nil) ?: @"";
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(text, status);
		});
	});
}

+ (void)uninstallGameWithSerial:(NSString*)serial completion:(void (^)(NSInteger status))completion
{
	NSString* game = [serial copy];
	dispatch_async(rpcs3_bridge_queue(), ^{
		const Rpcs3IOS_Status status = Rpcs3IOS_UninstallGame(game.UTF8String);
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(status);
		});
	});
}

+ (void)clearGameCacheWithSerial:(NSString*)serial completion:(void (^)(NSInteger status))completion
{
	NSString* game = [serial copy];
	dispatch_async(rpcs3_bridge_queue(), ^{
		const Rpcs3IOS_Status status = Rpcs3IOS_ClearGameCache(game.UTF8String);
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(status);
		});
	});
}

+ (void)hdd0DirectoryWithCompletion:(void (^)(NSString* path, NSInteger status))completion
{
	dispatch_async(rpcs3_bridge_queue(), ^{
		char dir[1024] = {0};
		const Rpcs3IOS_Status status = Rpcs3IOS_GetHdd0Dir(dir, sizeof dir);
		NSString* text = (status == RPCS3_IOS_STATUS_OK ? utf8_string(dir) : nil) ?: @"";
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(text, status);
		});
	});
}

@end
