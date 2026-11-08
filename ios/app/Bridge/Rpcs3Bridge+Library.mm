// SPDX-License-Identifier: GPL-2.0-only

#import "Rpcs3BridgeInternal.h"

// nil for an empty or unconvertible path: the ABI reports "no such file" as an empty string.
static NSString* optional_path(const char* text)
{
	NSString* path = utf8_string(text);
	return path.length != 0 ? path : nil;
}

// A serial, title or path that is not valid UTF-8 would be nil in a nonnull property, so that title is skipped.
static void append_library_entry(void* context, const Rpcs3IOS_LibraryEntry* entry)
{
	NSString* serial = utf8_string(entry->serial);
	NSString* title = utf8_string(entry->title);
	NSString* boot_path = utf8_string(entry->boot_path);
	if (serial == nil || title == nil || boot_path == nil)
	{
		return;
	}
	NSMutableArray* games = (__bridge NSMutableArray*)context;
	[games addObject:[[Rpcs3GameEntry alloc] initWithSerial:serial title:title category:(utf8_string(entry->category) ?: @"")
		bootPath:boot_path iconPath:optional_path(entry->icon_path) backdropPath:optional_path(entry->backdrop_path)
		isISO:entry->is_iso != 0 fromGamesYml:entry->source == 1]];
}

@implementation Rpcs3Bridge (Library)

+ (void)scanLibraryWithCompletion:(void (^)(NSArray<Rpcs3GameEntry*>* games, NSInteger status))completion
{
	dispatch_async(rpcs3_bridge_queue(), ^{
		NSMutableArray* games = [NSMutableArray array];
		const Rpcs3IOS_Status status = Rpcs3IOS_ScanLibrary(append_library_entry, (__bridge void*)games);
		NSArray<Rpcs3GameEntry*>* result = [games copy];
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(result, status);
		});
	});
}

+ (void)registerGameFolderAtPath:(NSString*)path completion:(void (^)(NSInteger status, NSUInteger added))completion
{
	NSString* folder = [path copy];
	dispatch_async(rpcs3_bridge_queue(), ^{
		uint32_t added = 0;
		const Rpcs3IOS_Status status = Rpcs3IOS_RegisterGameFolder(folder.UTF8String, &added);
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(status, added);
		});
	});
}

@end
