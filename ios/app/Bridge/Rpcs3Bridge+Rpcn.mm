// SPDX-License-Identifier: GPL-2.0-only

#import "Rpcs3BridgeInternal.h"

// Blocking RPCN calls get their own serial queue, so a boot or a stop never waits behind the network.
static dispatch_queue_t rpcs3_rpcn_queue(void)
{
	static dispatch_queue_t queue;
	static dispatch_once_t once;
	dispatch_once(&once, ^{
		queue = dispatch_queue_create("armsx3.rpcn",
			dispatch_queue_attr_make_with_qos_class(DISPATCH_QUEUE_SERIAL, QOS_CLASS_USER_INITIATED, 0));
	});
	return queue;
}

@implementation Rpcs3Bridge (Rpcn)

// Both strings are copied before the hop and never logged.
+ (void)rpcnSetSecret:(NSString*)secret token:(NSString*)token completion:(void (^)(NSInteger status))completion
{
	NSString* secretCopy = [secret copy];
	NSString* tokenCopy = [token copy];
	void (^done)(NSInteger) = [completion copy];
	dispatch_async(rpcs3_rpcn_queue(), ^{
		const Rpcs3IOS_Status status = Rpcs3IOS_RpcnSetSecret(secretCopy.UTF8String, tokenCopy.UTF8String);
		dispatch_async(dispatch_get_main_queue(), ^{
			done(status);
		});
	});
}

// The accepted form comes back in a stack buffer zeroed with memset_s, which the compiler may not remove.
+ (void)rpcnSignIn:(NSString*)npid password:(NSString*)password token:(NSString*)token
	completion:(void (^)(NSInteger status, NSInteger state, NSInteger error, NSInteger raw, NSString* _Nullable secret))completion
{
	NSString* npidCopy = [npid copy];
	NSString* passwordCopy = [password copy];
	NSString* tokenCopy = [token copy];
	void (^done)(NSInteger, NSInteger, NSInteger, NSInteger, NSString*) = [completion copy];
	dispatch_async(rpcs3_rpcn_queue(), ^{
		char buffer[65] = {0};
		Rpcs3IOS_RpcnResult result = {0, 0, 0};
		const Rpcs3IOS_Status status = Rpcs3IOS_RpcnSignIn(npidCopy.UTF8String, passwordCopy.UTF8String, tokenCopy.UTF8String,
			buffer, (uint32_t)sizeof(buffer), &result);
		NSString* secret = status == RPCS3_IOS_STATUS_OK && buffer[0] != '\0' ? [NSString stringWithUTF8String:buffer] : nil;
		memset_s(buffer, sizeof(buffer), 0, sizeof(buffer));
		dispatch_async(dispatch_get_main_queue(), ^{
			done(status, result.state, result.error, result.raw, secret);
		});
	});
}

// Behind any sign-in still running.
+ (void)rpcnSignOutWithCompletion:(void (^)(NSInteger status))completion
{
	void (^done)(NSInteger) = [completion copy];
	dispatch_async(rpcs3_rpcn_queue(), ^{
		const Rpcs3IOS_Status status = Rpcs3IOS_RpcnSignOut();
		dispatch_async(dispatch_get_main_queue(), ^{
			done(status);
		});
	});
}

// Peek-only, so a global queue that never waits behind a sign-in.
+ (void)rpcnConfigWithCompletion:(void (^)(NSInteger status, NSData* _Nullable json))completion
{
	void (^done)(NSInteger, NSData*) = [completion copy];
	dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
		NSMutableData* text = [NSMutableData data];
		const Rpcs3IOS_Status status = Rpcs3IOS_RpcnConfig(append_settings_text, (__bridge void*)text);
		NSData* json = status == RPCS3_IOS_STATUS_OK ? [text copy] : nil;
		dispatch_async(dispatch_get_main_queue(), ^{
			done(status, json);
		});
	});
}

// Peek-only, so a global queue. No field of the JSON is logged.
+ (void)rpcnStatusWithCompletion:(void (^)(NSInteger status, NSData* _Nullable json))completion
{
	void (^done)(NSInteger, NSData*) = [completion copy];
	dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
		NSMutableData* text = [NSMutableData data];
		const Rpcs3IOS_Status status = Rpcs3IOS_RpcnStatus(append_settings_text, (__bridge void*)text);
		NSData* json = status == RPCS3_IOS_STATUS_OK ? [text copy] : nil;
		dispatch_async(dispatch_get_main_queue(), ^{
			done(status, json);
		});
	});
}

// The strings are copied before the hop and never logged.
+ (void)rpcnCreateAccount:(NSString*)npid password:(NSString*)password email:(NSString*)email
	completion:(void (^)(NSInteger status, NSInteger state, NSInteger error, NSInteger raw))completion
{
	NSString* npidCopy = [npid copy];
	NSString* passwordCopy = [password copy];
	NSString* emailCopy = [email copy];
	void (^done)(NSInteger, NSInteger, NSInteger, NSInteger) = [completion copy];
	dispatch_async(rpcs3_rpcn_queue(), ^{
		Rpcs3IOS_RpcnResult result = {0, 0, 0};
		const Rpcs3IOS_Status status = Rpcs3IOS_RpcnCreateAccount(npidCopy.UTF8String, passwordCopy.UTF8String,
			emailCopy.UTF8String, &result);
		dispatch_async(dispatch_get_main_queue(), ^{
			done(status, result.state, result.error, result.raw);
		});
	});
}

// The strings are copied before the hop and never logged.
+ (void)rpcnResendToken:(NSString*)npid password:(NSString*)password
	completion:(void (^)(NSInteger status, NSInteger state, NSInteger error, NSInteger raw))completion
{
	NSString* npidCopy = [npid copy];
	NSString* passwordCopy = [password copy];
	void (^done)(NSInteger, NSInteger, NSInteger, NSInteger) = [completion copy];
	dispatch_async(rpcs3_rpcn_queue(), ^{
		Rpcs3IOS_RpcnResult result = {0, 0, 0};
		const Rpcs3IOS_Status status = Rpcs3IOS_RpcnResendToken(npidCopy.UTF8String, passwordCopy.UTF8String, &result);
		dispatch_async(dispatch_get_main_queue(), ^{
			done(status, result.state, result.error, result.raw);
		});
	});
}

+ (void)rpcnSendResetToken:(NSString*)npid email:(NSString*)email
	completion:(void (^)(NSInteger status, NSInteger state, NSInteger error, NSInteger raw))completion
{
	NSString* npidCopy = [npid copy];
	NSString* emailCopy = [email copy];
	void (^done)(NSInteger, NSInteger, NSInteger, NSInteger) = [completion copy];
	dispatch_async(rpcs3_rpcn_queue(), ^{
		Rpcs3IOS_RpcnResult result = {0, 0, 0};
		const Rpcs3IOS_Status status = Rpcs3IOS_RpcnSendResetToken(npidCopy.UTF8String, emailCopy.UTF8String, &result);
		dispatch_async(dispatch_get_main_queue(), ^{
			done(status, result.state, result.error, result.raw);
		});
	});
}

+ (void)rpcnResetPassword:(NSString*)npid code:(NSString*)code password:(NSString*)password
	completion:(void (^)(NSInteger status, NSInteger state, NSInteger error, NSInteger raw))completion
{
	NSString* npidCopy = [npid copy];
	NSString* codeCopy = [code copy];
	NSString* passwordCopy = [password copy];
	void (^done)(NSInteger, NSInteger, NSInteger, NSInteger) = [completion copy];
	dispatch_async(rpcs3_rpcn_queue(), ^{
		Rpcs3IOS_RpcnResult result = {0, 0, 0};
		const Rpcs3IOS_Status status = Rpcs3IOS_RpcnResetPassword(npidCopy.UTF8String, codeCopy.UTF8String,
			passwordCopy.UTF8String, &result);
		dispatch_async(dispatch_get_main_queue(), ^{
			done(status, result.state, result.error, result.raw);
		});
	});
}

+ (void)rpcnFriendsSession:(BOOL)open
	completion:(void (^)(NSInteger status, NSInteger state, NSInteger error, NSInteger raw))completion
{
	void (^done)(NSInteger, NSInteger, NSInteger, NSInteger) = [completion copy];
	dispatch_async(rpcs3_rpcn_queue(), ^{
		Rpcs3IOS_RpcnResult result = {0, 0, 0};
		const Rpcs3IOS_Status status = Rpcs3IOS_RpcnFriendsSession(open ? 1 : 0, &result);
		dispatch_async(dispatch_get_main_queue(), ^{
			done(status, result.state, result.error, result.raw);
		});
	});
}

// Peek-only, so a global queue. No field of the JSON is logged.
+ (void)rpcnFriendsWithCompletion:(void (^)(NSInteger status, NSData* _Nullable json))completion
{
	void (^done)(NSInteger, NSData*) = [completion copy];
	dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
		NSMutableData* text = [NSMutableData data];
		const Rpcs3IOS_Status status = Rpcs3IOS_RpcnFriends(append_settings_text, (__bridge void*)text);
		NSData* json = status == RPCS3_IOS_STATUS_OK ? [text copy] : nil;
		dispatch_async(dispatch_get_main_queue(), ^{
			done(status, json);
		});
	});
}

// The name is copied before the hop and never logged.
+ (void)rpcnAddFriend:(NSString*)npid
	completion:(void (^)(NSInteger status, NSInteger state, NSInteger error, NSInteger raw))completion
{
	NSString* npidCopy = [npid copy];
	void (^done)(NSInteger, NSInteger, NSInteger, NSInteger) = [completion copy];
	dispatch_async(rpcs3_rpcn_queue(), ^{
		Rpcs3IOS_RpcnResult result = {0, 0, 0};
		const Rpcs3IOS_Status status = Rpcs3IOS_RpcnAddFriend(npidCopy.UTF8String, &result);
		dispatch_async(dispatch_get_main_queue(), ^{
			done(status, result.state, result.error, result.raw);
		});
	});
}

+ (void)rpcnRemoveFriend:(NSString*)npid
	completion:(void (^)(NSInteger status, NSInteger state, NSInteger error, NSInteger raw))completion
{
	NSString* npidCopy = [npid copy];
	void (^done)(NSInteger, NSInteger, NSInteger, NSInteger) = [completion copy];
	dispatch_async(rpcs3_rpcn_queue(), ^{
		Rpcs3IOS_RpcnResult result = {0, 0, 0};
		const Rpcs3IOS_Status status = Rpcs3IOS_RpcnRemoveFriend(npidCopy.UTF8String, &result);
		dispatch_async(dispatch_get_main_queue(), ^{
			done(status, result.state, result.error, result.raw);
		});
	});
}

// Peek-only, so a global queue. No field of the JSON is logged.
+ (void)rpcnInvitesWithCompletion:(void (^)(NSInteger status, NSData* _Nullable json))completion
{
	void (^done)(NSInteger, NSData*) = [completion copy];
	dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
		NSMutableData* text = [NSMutableData data];
		const Rpcs3IOS_Status status = Rpcs3IOS_RpcnInvites(append_settings_text, (__bridge void*)text);
		NSData* json = status == RPCS3_IOS_STATUS_OK ? [text copy] : nil;
		dispatch_async(dispatch_get_main_queue(), ^{
			done(status, json);
		});
	});
}

+ (void)rpcnAcceptInvite:(uint64_t)inviteId completion:(void (^)(NSInteger status))completion
{
	void (^done)(NSInteger) = [completion copy];
	dispatch_async(rpcs3_rpcn_queue(), ^{
		const Rpcs3IOS_Status status = Rpcs3IOS_RpcnAcceptInvite(inviteId);
		dispatch_async(dispatch_get_main_queue(), ^{
			done(status);
		});
	});
}

+ (void)rpcnDeclineInvite:(uint64_t)inviteId completion:(void (^)(NSInteger status))completion
{
	void (^done)(NSInteger) = [completion copy];
	dispatch_async(rpcs3_rpcn_queue(), ^{
		const Rpcs3IOS_Status status = Rpcs3IOS_RpcnDeclineInvite(inviteId);
		dispatch_async(dispatch_get_main_queue(), ^{
			done(status);
		});
	});
}

// The strings are copied before the hop and never logged.
+ (void)rpcnAddHost:(NSString*)name host:(NSString*)host completion:(void (^)(NSInteger status))completion
{
	NSString* nameCopy = [name copy];
	NSString* hostCopy = [host copy];
	void (^done)(NSInteger) = [completion copy];
	dispatch_async(rpcs3_rpcn_queue(), ^{
		const Rpcs3IOS_Status status = Rpcs3IOS_RpcnAddHost(nameCopy.UTF8String, hostCopy.UTF8String);
		dispatch_async(dispatch_get_main_queue(), ^{
			done(status);
		});
	});
}

+ (void)rpcnDelHost:(NSString*)name host:(NSString*)host completion:(void (^)(NSInteger status))completion
{
	NSString* nameCopy = [name copy];
	NSString* hostCopy = [host copy];
	void (^done)(NSInteger) = [completion copy];
	dispatch_async(rpcs3_rpcn_queue(), ^{
		const Rpcs3IOS_Status status = Rpcs3IOS_RpcnDelHost(nameCopy.UTF8String, hostCopy.UTF8String);
		dispatch_async(dispatch_get_main_queue(), ^{
			done(status);
		});
	});
}

+ (void)rpcnResetHostsWithCompletion:(void (^)(NSInteger status))completion
{
	void (^done)(NSInteger) = [completion copy];
	dispatch_async(rpcs3_rpcn_queue(), ^{
		const Rpcs3IOS_Status status = Rpcs3IOS_RpcnResetHosts();
		dispatch_async(dispatch_get_main_queue(), ^{
			done(status);
		});
	});
}

+ (void)rpcnSelectHost:(NSString*)host completion:(void (^)(NSInteger status))completion
{
	NSString* hostCopy = [host copy];
	void (^done)(NSInteger) = [completion copy];
	dispatch_async(rpcs3_rpcn_queue(), ^{
		const Rpcs3IOS_Status status = Rpcs3IOS_RpcnSelectHost(hostCopy.UTF8String);
		dispatch_async(dispatch_get_main_queue(), ^{
			done(status);
		});
	});
}

+ (void)rpcnSetIpv6:(BOOL)on completion:(void (^)(NSInteger status))completion
{
	void (^done)(NSInteger) = [completion copy];
	dispatch_async(rpcs3_rpcn_queue(), ^{
		const Rpcs3IOS_Status status = Rpcs3IOS_RpcnSetIpv6(on ? 1 : 0);
		dispatch_async(dispatch_get_main_queue(), ^{
			done(status);
		});
	});
}

+ (void)rpcnDeleteTrophiesWithCompletion:(void (^)(NSInteger status, NSInteger state, NSInteger error, NSInteger raw))completion
{
	void (^done)(NSInteger, NSInteger, NSInteger, NSInteger) = [completion copy];
	dispatch_async(rpcs3_rpcn_queue(), ^{
		Rpcs3IOS_RpcnResult result = {0, 0, 0};
		const Rpcs3IOS_Status status = Rpcs3IOS_RpcnDeleteTrophies(&result);
		dispatch_async(dispatch_get_main_queue(), ^{
			done(status, result.state, result.error, result.raw);
		});
	});
}

// A large library can take seconds. No field of either JSON is logged.
+ (void)avatarListWithCompletion:(void (^)(NSInteger status, NSData* _Nullable json))completion
{
	void (^done)(NSInteger, NSData*) = [completion copy];
	dispatch_async(rpcs3_rpcn_queue(), ^{
		NSMutableData* text = [NSMutableData data];
		const Rpcs3IOS_Status status = Rpcs3IOS_AvatarList(append_settings_text, (__bridge void*)text);
		NSData* json = status == RPCS3_IOS_STATUS_OK ? [text copy] : nil;
		dispatch_async(dispatch_get_main_queue(), ^{
			done(status, json);
		});
	});
}

+ (void)avatarCurrentWithCompletion:(void (^)(NSInteger status, NSData* _Nullable json))completion
{
	void (^done)(NSInteger, NSData*) = [completion copy];
	dispatch_async(rpcs3_rpcn_queue(), ^{
		NSMutableData* text = [NSMutableData data];
		const Rpcs3IOS_Status status = Rpcs3IOS_AvatarCurrent(append_settings_text, (__bridge void*)text);
		NSData* json = status == RPCS3_IOS_STATUS_OK ? [text copy] : nil;
		dispatch_async(dispatch_get_main_queue(), ^{
			done(status, json);
		});
	});
}

// The path is copied before the hop and never logged.
+ (void)avatarSelect:(NSString*)path completion:(void (^)(NSInteger status))completion
{
	NSString* pathCopy = [path copy];
	void (^done)(NSInteger) = [completion copy];
	dispatch_async(rpcs3_rpcn_queue(), ^{
		const Rpcs3IOS_Status status = Rpcs3IOS_AvatarSelect(pathCopy.UTF8String);
		dispatch_async(dispatch_get_main_queue(), ^{
			done(status);
		});
	});
}

+ (void)avatarImage:(NSString*)path completion:(void (^)(NSInteger status, NSData* _Nullable png))completion
{
	NSString* pathCopy = [path copy];
	void (^done)(NSInteger, NSData*) = [completion copy];
	dispatch_async(rpcs3_rpcn_queue(), ^{
		NSMutableData* bytes = [NSMutableData data];
		const Rpcs3IOS_Status status = Rpcs3IOS_AvatarImage(pathCopy.UTF8String, append_settings_text, (__bridge void*)bytes);
		NSData* png = status == RPCS3_IOS_STATUS_OK ? [bytes copy] : nil;
		dispatch_async(dispatch_get_main_queue(), ^{
			done(status, png);
		});
	});
}

+ (void)avatarUseImage:(NSData*)png completion:(void (^)(NSInteger status))completion
{
	NSData* pngCopy = [png copy];
	void (^done)(NSInteger) = [completion copy];
	dispatch_async(rpcs3_rpcn_queue(), ^{
		// Past 4 GiB the length is passed as UINT32_MAX, which the 16 MiB rule refuses, never truncated into range.
		const uint32_t size = pngCopy.length > UINT32_MAX ? UINT32_MAX : static_cast<uint32_t>(pngCopy.length);
		const Rpcs3IOS_Status status = Rpcs3IOS_AvatarUseImage(static_cast<const uint8_t*>(pngCopy.bytes), size);
		dispatch_async(dispatch_get_main_queue(), ^{
			done(status);
		});
	});
}

+ (void)avatarUseDefaultWithCompletion:(void (^)(NSInteger status))completion
{
	void (^done)(NSInteger) = [completion copy];
	dispatch_async(rpcs3_rpcn_queue(), ^{
		const Rpcs3IOS_Status status = Rpcs3IOS_AvatarUseDefault();
		dispatch_async(dispatch_get_main_queue(), ^{
			done(status);
		});
	});
}

@end
