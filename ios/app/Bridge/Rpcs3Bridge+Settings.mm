// SPDX-License-Identifier: GPL-2.0-only

#import "Rpcs3BridgeInternal.h"

@implementation Rpcs3Bridge (Settings)

+ (void)setLiveSetting:(NSInteger)which value:(NSInteger)value completion:(void (^)(NSInteger status))completion
{
	dispatch_async(rpcs3_bridge_queue(), ^{
		const Rpcs3IOS_Status status = Rpcs3IOS_SetLiveSetting(live_int32(which), live_int32(value));
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(status);
		});
	});
}

+ (void)liveSetting:(NSInteger)which completion:(void (^)(NSInteger value, NSInteger status))completion
{
	dispatch_async(rpcs3_bridge_queue(), ^{
		int32_t value = 0;
		const Rpcs3IOS_Status status = Rpcs3IOS_GetLiveSetting(live_int32(which), &value);
		const NSInteger shown = status == RPCS3_IOS_STATUS_OK ? value : 0;
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(shown, status);
		});
	});
}

+ (void)settingsForSerial:(nullable NSString*)serial completion:(void (^)(NSData* _Nullable json, NSInteger status))completion
{
	NSString* scope = [serial copy];
	dispatch_async(rpcs3_bridge_queue(), ^{
		NSMutableData* text = [NSMutableData data];
		// s_product_config is read here, on the façade queue, as +loadStateAtPath:game:completion: reads it.
		const Rpcs3IOS_Status status = Rpcs3IOS_SettingsDump(scope.UTF8String,
			s_product_config[0] != '\0' ? s_product_config : NULL, append_settings_text, (__bridge void*)text);
		NSData* json = status == RPCS3_IOS_STATUS_OK ? [text copy] : nil;
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(json, status);
		});
	});
}

+ (void)setSetting:(NSString*)path value:(NSString*)value serial:(nullable NSString*)serial
	completion:(void (^)(NSInteger status, BOOL live))completion
{
	NSString* key = [path copy];
	NSString* text = [value copy];
	NSString* scope = [serial copy];
	dispatch_async(rpcs3_bridge_queue(), ^{
		int32_t live = 0;
		const Rpcs3IOS_Status status = Rpcs3IOS_SettingsSet(scope.UTF8String, key.UTF8String, text.UTF8String,
			s_product_config[0] != '\0' ? s_product_config : NULL, &live);
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(status, live != 0);
		});
	});
}

+ (void)resetSettings:(NSArray<NSString*>*)paths serial:(nullable NSString*)serial
	completion:(void (^)(NSInteger status, BOOL live))completion
{
	NSArray<NSString*>* keys = [[NSArray alloc] initWithArray:paths copyItems:YES];
	NSString* scope = [serial copy];
	dispatch_async(rpcs3_bridge_queue(), ^{
		// Copied into C++ strings first, so every pointer handed to the ABI outlives the call.
		std::vector<std::string> owned;
		for (NSString* key in keys)
		{
			owned.emplace_back(key.UTF8String != NULL ? key.UTF8String : "");
		}
		std::vector<const char*> raw;
		for (const std::string& key : owned)
		{
			raw.push_back(key.c_str());
		}
		int32_t live = 0;
		const Rpcs3IOS_Status status = Rpcs3IOS_SettingsReset(scope.UTF8String, raw.empty() ? NULL : raw.data(),
			(uint32_t)raw.size(), s_product_config[0] != '\0' ? s_product_config : NULL, &live);
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(status, live != 0);
		});
	});
}

+ (void)liveSettingAtPath:(NSString*)path completion:(void (^)(NSString* value, NSInteger status))completion
{
	NSString* key = [path copy];
	dispatch_async(rpcs3_bridge_queue(), ^{
		char value[512] = {};
		const Rpcs3IOS_Status status = Rpcs3IOS_SettingsGetLive(key.UTF8String, value, sizeof(value));
		NSString* shown = status == RPCS3_IOS_STATUS_OK ? ([NSString stringWithUTF8String:value] ?: @"") : @"";
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(shown, status);
		});
	});
}

// Bytes and a length, never a C string, so an embedded NUL reaches the bridge's own check.
+ (void)normaliseSettings:(NSString*)yaml
	completion:(void (^)(NSString* yaml, NSInteger kept, NSInteger unknown, NSInteger defaulted, NSInteger locked,
		NSInteger status))completion
{
	NSData* bytes = [[yaml copy] dataUsingEncoding:NSUTF8StringEncoding] ?: [NSData data];
	dispatch_async(rpcs3_bridge_queue(), ^{
		NSMutableData* text = [NSMutableData data];
		Rpcs3IOS_SettingsCounts counts = {};
		const Rpcs3IOS_Status status = Rpcs3IOS_SettingsNormalise(bytes.length != 0 ? (const char*)bytes.bytes : "",
			(uint32_t)bytes.length, s_product_config[0] != '\0' ? s_product_config : NULL, append_settings_text,
			(__bridge void*)text, &counts);
		NSString* shown = status == RPCS3_IOS_STATUS_OK
			? ([[NSString alloc] initWithData:text encoding:NSUTF8StringEncoding] ?: @"") : @"";
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(shown, counts.kept, counts.unknown, counts.defaulted, counts.locked, status);
		});
	});
}

+ (void)applySettings:(NSString*)yaml serial:(nullable NSString*)serial
	completion:(void (^)(NSInteger changed, BOOL live, NSInteger status))completion
{
	NSData* bytes = [[yaml copy] dataUsingEncoding:NSUTF8StringEncoding] ?: [NSData data];
	NSString* scope = [serial copy];
	dispatch_async(rpcs3_bridge_queue(), ^{
		uint32_t changed = 0;
		int32_t live = 0;
		const Rpcs3IOS_Status status = Rpcs3IOS_SettingsApply(scope.UTF8String,
			bytes.length != 0 ? (const char*)bytes.bytes : "", (uint32_t)bytes.length,
			s_product_config[0] != '\0' ? s_product_config : NULL, &changed, &live);
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(changed, live != 0, status);
		});
	});
}

+ (void)exportSettingsForSerial:(nullable NSString*)serial
	completion:(void (^)(NSString* yaml, NSInteger status))completion
{
	NSString* scope = [serial copy];
	dispatch_async(rpcs3_bridge_queue(), ^{
		NSMutableData* text = [NSMutableData data];
		const Rpcs3IOS_Status status = Rpcs3IOS_SettingsExport(scope.UTF8String,
			s_product_config[0] != '\0' ? s_product_config : NULL, append_settings_text, (__bridge void*)text);
		NSString* shown = status == RPCS3_IOS_STATUS_OK
			? ([[NSString alloc] initWithData:text encoding:NSUTF8StringEncoding] ?: @"") : @"";
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(shown, status);
		});
	});
}

+ (void)patchEngineVersionWithCompletion:(void (^)(NSString* version, NSInteger status))completion
{
	dispatch_async(rpcs3_bridge_queue(), ^{
		char version[32] = {0};
		const Rpcs3IOS_Status status = Rpcs3IOS_PatchEngineVersion(version, sizeof version);
		NSString* text = (status == RPCS3_IOS_STATUS_OK ? utf8_string(version) : nil) ?: @"";
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(text, status);
		});
	});
}

+ (void)writePatchFile:(NSString*)fileName yaml:(NSString*)yaml completion:(void (^)(NSInteger status))completion
{
	NSString* name = [fileName copy];
	NSString* body = [yaml copy];
	dispatch_async(rpcs3_bridge_queue(), ^{
		const Rpcs3IOS_Status status = Rpcs3IOS_PatchesWrite(name.UTF8String ?: "", body.UTF8String ?: "");
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(status);
		});
	});
}

+ (void)importPatches:(NSString*)yaml completion:(void (^)(NSInteger status))completion
{
	NSString* body = [yaml copy];
	dispatch_async(rpcs3_bridge_queue(), ^{
		const Rpcs3IOS_Status status = Rpcs3IOS_PatchesImport(body.UTF8String ?: "");
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(status);
		});
	});
}

+ (void)patchesForSerial:(NSString*)serial completion:(void (^)(NSData* json, NSInteger status))completion
{
	NSString* scope = [serial copy];
	dispatch_async(rpcs3_bridge_queue(), ^{
		NSMutableData* text = [NSMutableData data];
		const Rpcs3IOS_Status status = Rpcs3IOS_PatchesList(scope.UTF8String ?: "", append_settings_text, (__bridge void*)text);
		NSData* shown = status == RPCS3_IOS_STATUS_OK ? [text copy] : [NSData data];
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(shown, status);
		});
	});
}

+ (void)setPatchEnabled:(BOOL)enabled hash:(NSString*)hash patchDescription:(NSString*)patchDescription
	serial:(NSString*)serial appVersion:(NSString*)appVersion completion:(void (^)(NSInteger status))completion
{
	NSString* h = [hash copy];
	NSString* d = [patchDescription copy];
	NSString* s = [serial copy];
	NSString* v = [appVersion copy];
	dispatch_async(rpcs3_bridge_queue(), ^{
		const Rpcs3IOS_Status status = Rpcs3IOS_PatchSetEnabled(h.UTF8String ?: "", d.UTF8String ?: "", s.UTF8String ?: "",
			v.UTF8String ?: "", enabled ? 1 : 0);
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(status);
		});
	});
}

@end
