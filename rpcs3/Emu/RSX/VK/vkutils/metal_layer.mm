#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wold-style-cast"
#pragma GCC diagnostic ignored "-Wdeprecated-declarations"
#pragma GCC diagnostic ignored "-Wmissing-declarations"
#import <Foundation/Foundation.h>
// Neither AppKit nor NSView exists in the iOS SDK, so iOS takes the UIView layer instead.
#include <TargetConditionals.h>
#if TARGET_OS_IPHONE
#import <UIKit/UIKit.h>
#else
#import <AppKit/AppKit.h>
#endif
#import <QuartzCore/QuartzCore.h>

void* GetCAMetalLayerFromMetalView(void* view)
{
#if TARGET_OS_IPHONE
	return ((UIView*)view).layer;
#else
	return ((NSView*)view).layer;
#endif
}
#pragma GCC diagnostic pop
