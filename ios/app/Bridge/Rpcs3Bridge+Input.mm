// SPDX-License-Identifier: GPL-2.0-only

#import "Rpcs3BridgeInternal.h"

@implementation Rpcs3Bridge (Input)

// On the bridge queue, since these are not pad calls.
+ (void)inputConfigForSerial:(nullable NSString*)serial completion:(void (^)(NSData* _Nullable json, NSInteger status))completion
{
	NSString* scope = [serial copy];
	dispatch_async(rpcs3_bridge_queue(), ^{
		NSMutableData* text = [NSMutableData data];
		const Rpcs3IOS_Status status = Rpcs3IOS_InputConfigDump(scope.UTF8String, append_settings_text, (__bridge void*)text);
		NSData* json = status == RPCS3_IOS_STATUS_OK ? [text copy] : nil;
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(json, status);
		});
	});
}

+ (void)setInputBinding:(NSString*)key value:(NSString*)value player:(NSInteger)player serial:(nullable NSString*)serial completion:(void (^)(NSInteger status))completion
{
	NSString* name = [key copy];
	NSString* text = [value copy];
	NSString* scope = [serial copy];
	dispatch_async(rpcs3_bridge_queue(), ^{
		const Rpcs3IOS_Status status = Rpcs3IOS_InputConfigSet(scope.UTF8String, live_int32(player), name.UTF8String, text.UTF8String);
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(status);
		});
	});
}

+ (void)resetInputPlayer:(NSInteger)player serial:(nullable NSString*)serial completion:(void (^)(NSInteger status))completion
{
	NSString* scope = [serial copy];
	dispatch_async(rpcs3_bridge_queue(), ^{
		const Rpcs3IOS_Status status = Rpcs3IOS_InputConfigReset(scope.UTF8String, live_int32(player));
		dispatch_async(dispatch_get_main_queue(), ^{
			completion(status);
		});
	});
}

+ (void)setPlayer:(NSInteger)player device:(NSInteger)productType serial:(nullable NSString*)serial completion:(void (^)(NSInteger status))completion
{
	NSString* scope = [serial copy];
	void (^done)(NSInteger) = [completion copy];
	dispatch_async(rpcs3_bridge_queue(), ^{
		const Rpcs3IOS_Status status = Rpcs3IOS_SetPlayerDevice(scope.UTF8String, live_int32(player), live_int32(productType));
		dispatch_async(dispatch_get_main_queue(), ^{
			done(status);
		});
	});
}

+ (void)deviceForPlayer:(NSInteger)player serial:(nullable NSString*)serial completion:(void (^)(NSInteger status, NSInteger productType))completion
{
	NSString* scope = [serial copy];
	void (^done)(NSInteger, NSInteger) = [completion copy];
	dispatch_async(rpcs3_bridge_queue(), ^{
		int32_t productType = 0;
		const Rpcs3IOS_Status status = Rpcs3IOS_GetPlayerDevice(scope.UTF8String, live_int32(player), &productType);
		dispatch_async(dispatch_get_main_queue(), ^{
			done(status, status == RPCS3_IOS_STATUS_OK ? productType : 0);
		});
	});
}

// Portal calls are lifecycle calls, so the bridge queue. The path is never logged.
+ (void)portalLoad:(NSInteger)portal slot:(NSInteger)slot path:(NSString*)path completion:(void (^)(NSInteger status))completion
{
	NSString* file = [path copy];
	void (^done)(NSInteger) = [completion copy];
	dispatch_async(rpcs3_bridge_queue(), ^{
		const Rpcs3IOS_Status status = Rpcs3IOS_PortalLoad(live_int32(portal), live_int32(slot), file.UTF8String);
		dispatch_async(dispatch_get_main_queue(), ^{
			done(status);
		});
	});
}

+ (void)portalRemove:(NSInteger)portal slot:(NSInteger)slot completion:(void (^)(NSInteger status))completion
{
	void (^done)(NSInteger) = [completion copy];
	dispatch_async(rpcs3_bridge_queue(), ^{
		const Rpcs3IOS_Status status = Rpcs3IOS_PortalRemove(live_int32(portal), live_int32(slot));
		dispatch_async(dispatch_get_main_queue(), ^{
			done(status);
		});
	});
}

+ (void)portalList:(NSInteger)portal completion:(void (^)(NSInteger status, NSData* _Nullable json))completion
{
	void (^done)(NSInteger, NSData*) = [completion copy];
	dispatch_async(rpcs3_bridge_queue(), ^{
		NSMutableData* text = [NSMutableData data];
		const Rpcs3IOS_Status status = Rpcs3IOS_PortalList(live_int32(portal), append_settings_text, (__bridge void*)text);
		NSData* json = status == RPCS3_IOS_STATUS_OK ? [text copy] : nil;
		dispatch_async(dispatch_get_main_queue(), ^{
			done(status, json);
		});
	});
}

// The path is never logged.
+ (void)portalCreate:(NSInteger)portal figure:(NSInteger)figureID variant:(NSInteger)variant path:(NSString*)path
	completion:(void (^)(NSInteger status))completion
{
	NSString* file = [path copy];
	void (^done)(NSInteger) = [completion copy];
	dispatch_async(rpcs3_bridge_queue(), ^{
		const Rpcs3IOS_Status status =
			Rpcs3IOS_PortalCreate(live_int32(portal), live_int32(figureID), live_int32(variant), file.UTF8String);
		dispatch_async(dispatch_get_main_queue(), ^{
			done(status);
		});
	});
}

+ (void)setPadStateButtons:(uint32_t)mask leftX:(uint8_t)lx leftY:(uint8_t)ly rightX:(uint8_t)rx rightY:(uint8_t)ry
	l2:(uint8_t)l2 r2:(uint8_t)r2 completion:(nullable void (^)(NSInteger status))completion
{
	void (^done)(NSInteger) = [completion copy];
	dispatch_async(rpcs3_input_queue(), ^{
		const Rpcs3IOS_Status status = Rpcs3IOS_SetPadState(mask, lx, ly, rx, ry, l2, r2);
		if (done != nil)
		{
			dispatch_async(dispatch_get_main_queue(), ^{
				done(status);
			});
		}
	});
}

+ (void)setPlayer:(NSInteger)player padStateButtons:(uint32_t)mask leftX:(uint8_t)lx leftY:(uint8_t)ly rightX:(uint8_t)rx
	rightY:(uint8_t)ry l2:(uint8_t)l2 r2:(uint8_t)r2 completion:(nullable void (^)(NSInteger status))completion
{
	void (^done)(NSInteger) = [completion copy];
	dispatch_async(rpcs3_input_queue(), ^{
		const Rpcs3IOS_Status status = Rpcs3IOS_SetPlayerPadState((int32_t)player, mask, lx, ly, rx, ry, l2, r2);
		if (done != nil)
		{
			dispatch_async(dispatch_get_main_queue(), ^{
				done(status);
			});
		}
	});
}

+ (void)setPlayer:(NSInteger)player connected:(BOOL)connected completion:(nullable void (^)(NSInteger status))completion
{
	void (^done)(NSInteger) = [completion copy];
	dispatch_async(rpcs3_input_queue(), ^{
		const Rpcs3IOS_Status status = Rpcs3IOS_SetPlayerConnected((int32_t)player, connected ? 1 : 0);
		if (done != nil)
		{
			dispatch_async(dispatch_get_main_queue(), ^{
				done(status);
			});
		}
	});
}

+ (void)setPlayer:(NSInteger)player motionX:(uint16_t)x y:(uint16_t)y z:(uint16_t)z g:(uint16_t)g
	completion:(nullable void (^)(NSInteger status))completion
{
	void (^done)(NSInteger) = [completion copy];
	dispatch_async(rpcs3_input_queue(), ^{
		const Rpcs3IOS_Status status = Rpcs3IOS_SetPlayerMotion((int32_t)player, x, y, z, g);
		if (done != nil)
		{
			dispatch_async(dispatch_get_main_queue(), ^{
				done(status);
			});
		}
	});
}

+ (void)moveSphereColor:(NSInteger)gem completion:(void (^)(float r, float g, float b, NSInteger status))completion
{
	void (^done)(float, float, float, NSInteger) = [completion copy];
	dispatch_async(rpcs3_input_queue(), ^{
		float r = 0, g = 0, b = 0;
		const Rpcs3IOS_Status status = Rpcs3IOS_GetMoveColor((int32_t)gem, &r, &g, &b);
		dispatch_async(dispatch_get_main_queue(), ^{
			done(r, g, b, status);
		});
	});
}

+ (void)readRumbleWithCompletion:(void (^)(NSInteger status, NSData* motors))completion
{
	void (^done)(NSInteger, NSData*) = [completion copy];
	dispatch_async(rpcs3_input_queue(), ^{
		uint8_t bytes[8] = {0};
		NSInteger first = RPCS3_IOS_STATUS_OK;
		for (int32_t player = 0; player < 4; player++)
		{
			uint8_t large = 0, small = 0;
			const Rpcs3IOS_Status status = Rpcs3IOS_GetPlayerRumble(player, &large, &small);
			if (status == RPCS3_IOS_STATUS_OK)
			{
				bytes[player * 2] = large;
				bytes[player * 2 + 1] = small;
			}
			else if (first == RPCS3_IOS_STATUS_OK)
			{
				first = status;
			}
		}
		NSData* motors = [NSData dataWithBytes:bytes length:sizeof(bytes)];
		dispatch_async(dispatch_get_main_queue(), ^{
			done(first, motors);
		});
	});
}

+ (void)keyboardKey:(NSInteger)hidUsage pressed:(BOOL)pressed completion:(nullable void (^)(NSInteger status))completion
{
	void (^done)(NSInteger) = [completion copy];
	dispatch_async(rpcs3_input_queue(), ^{
		const Rpcs3IOS_Status status = Rpcs3IOS_KeyboardKey((int32_t)hidUsage, pressed ? 1 : 0);
		if (done != nil)
		{
			dispatch_async(dispatch_get_main_queue(), ^{
				done(status);
			});
		}
	});
}

+ (void)setKeyboardConnected:(BOOL)connected completion:(nullable void (^)(NSInteger status))completion
{
	void (^done)(NSInteger) = [completion copy];
	dispatch_async(rpcs3_input_queue(), ^{
		const Rpcs3IOS_Status status = Rpcs3IOS_SetKeyboardConnected(connected ? 1 : 0);
		if (done != nil)
		{
			dispatch_async(dispatch_get_main_queue(), ^{
				done(status);
			});
		}
	});
}

+ (void)mouseButton:(NSInteger)button pressed:(BOOL)pressed completion:(nullable void (^)(NSInteger status))completion
{
	void (^done)(NSInteger) = [completion copy];
	dispatch_async(rpcs3_input_queue(), ^{
		const Rpcs3IOS_Status status = Rpcs3IOS_MouseButton((int32_t)button, pressed ? 1 : 0);
		if (done != nil)
		{
			dispatch_async(dispatch_get_main_queue(), ^{
				done(status);
			});
		}
	});
}

+ (void)mouseMoveX:(NSInteger)x y:(NSInteger)y completion:(nullable void (^)(NSInteger status))completion
{
	void (^done)(NSInteger) = [completion copy];
	dispatch_async(rpcs3_input_queue(), ^{
		const Rpcs3IOS_Status status = Rpcs3IOS_MouseMove((int32_t)x, (int32_t)y);
		if (done != nil)
		{
			dispatch_async(dispatch_get_main_queue(), ^{
				done(status);
			});
		}
	});
}

+ (void)mouseScrollX:(NSInteger)x y:(NSInteger)y completion:(nullable void (^)(NSInteger status))completion
{
	void (^done)(NSInteger) = [completion copy];
	dispatch_async(rpcs3_input_queue(), ^{
		const Rpcs3IOS_Status status = Rpcs3IOS_MouseScroll((int32_t)x, (int32_t)y);
		if (done != nil)
		{
			dispatch_async(dispatch_get_main_queue(), ^{
				done(status);
			});
		}
	});
}

+ (void)setMouseConnected:(BOOL)connected completion:(nullable void (^)(NSInteger status))completion
{
	void (^done)(NSInteger) = [completion copy];
	dispatch_async(rpcs3_input_queue(), ^{
		const Rpcs3IOS_Status status = Rpcs3IOS_SetMouseConnected(connected ? 1 : 0);
		if (done != nil)
		{
			dispatch_async(dispatch_get_main_queue(), ^{
				done(status);
			});
		}
	});
}

+ (void)mouseAimX:(NSInteger)x y:(NSInteger)y maxX:(NSInteger)maxX maxY:(NSInteger)maxY
	completion:(nullable void (^)(NSInteger status))completion
{
	void (^done)(NSInteger) = [completion copy];
	dispatch_async(rpcs3_input_queue(), ^{
		const Rpcs3IOS_Status status = Rpcs3IOS_MouseAim((int32_t)x, (int32_t)y, (int32_t)maxX, (int32_t)maxY);
		if (done != nil)
		{
			dispatch_async(dispatch_get_main_queue(), ^{
				done(status);
			});
		}
	});
}

+ (void)mouseAimButton:(NSInteger)button pressed:(BOOL)pressed completion:(nullable void (^)(NSInteger status))completion
{
	void (^done)(NSInteger) = [completion copy];
	dispatch_async(rpcs3_input_queue(), ^{
		const Rpcs3IOS_Status status = Rpcs3IOS_MouseAimButton((int32_t)button, pressed ? 1 : 0);
		if (done != nil)
		{
			dispatch_async(dispatch_get_main_queue(), ^{
				done(status);
			});
		}
	});
}

+ (void)setPressureModifier:(BOOL)held completion:(nullable void (^)(NSInteger status))completion
{
	void (^done)(NSInteger) = [completion copy];
	dispatch_async(rpcs3_input_queue(), ^{
		const Rpcs3IOS_Status status = Rpcs3IOS_SetPressureModifier(held ? 1 : 0);
		if (done != nil)
		{
			dispatch_async(dispatch_get_main_queue(), ^{
				done(status);
			});
		}
	});
}

@end
