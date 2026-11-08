// SPDX-License-Identifier: GPL-2.0-only
// The AVAudioSession contract. Objective-C++ with AVFoundation, so it stays out of ios/bridge's plain C++.

#ifndef ARMSX3_IOS_AUDIO_SESSION_H
#define ARMSX3_IOS_AUDIO_SESSION_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

// Not a bool pair: never configured, refused activation and interrupted are three different bug reports.
typedef enum {
	ARMSX3_AUDIO_SESSION_UNCONFIGURED = 0, /* configure() has not run, or ran and failed */
	ARMSX3_AUDIO_SESSION_CONFIGURED,       /* category set, observers registered, not active */
	ARMSX3_AUDIO_SESSION_ACTIVE,           /* session active AND the stream is running */
	ARMSX3_AUDIO_SESSION_INTERRUPTED,      /* an interruption took the stream; waiting on .ended */
	ARMSX3_AUDIO_SESSION_ROUTE_PAUSED,     /* a route change paused playback; see last_branch */
	ARMSX3_AUDIO_SESSION_STOPPED,          /* stop() ran: stream stopped, session deactivated */
	ARMSX3_AUDIO_SESSION_FAILED            /* a named failure; see last_error/last_error_code */
} armsx3_audio_session_state_t;

// Fixed literals, so no user-chosen route or port name can leak through the status snapshot.
#define ARMSX3_AUDIO_BRANCH_NONE                     "none"
#define ARMSX3_AUDIO_BRANCH_START                    "start:activate-then-stream-start"
#define ARMSX3_AUDIO_BRANCH_STOP                     "stop:stream-stop-then-deactivate"
#define ARMSX3_AUDIO_BRANCH_INTERRUPTION_BEGAN       "interruption:began:destroy-stream"
#define ARMSX3_AUDIO_BRANCH_INTERRUPTION_RESUME      "interruption:ended:should-resume:recreate"
#define ARMSX3_AUDIO_BRANCH_INTERRUPTION_NO_RESUME   "interruption:ended:no-resume-hint:stay-stopped"
#define ARMSX3_AUDIO_BRANCH_ROUTE_OLD_DEVICE_GONE    "route:old-device-unavailable:pause"
#define ARMSX3_AUDIO_BRANCH_ROUTE_NO_OUTPUT          "route:no-output-attached:pause"
#define ARMSX3_AUDIO_BRANCH_ROUTE_RESTART            "route:other-reason:recreate-on-new-route"

// Destroy and recreate by name: a stream is not assumed to restart after an interruption. Hooks must not re-enter.
typedef int (*armsx3_audio_stream_fn)(void* ctx);

typedef struct {
	armsx3_audio_stream_fn create_and_start;
	armsx3_audio_stream_fn stop_and_destroy;
	void*                  ctx;
} armsx3_audio_stream_controller_t;

// A plain struct of computed values, so no caller reads a field mid-transition.
typedef struct {
	armsx3_audio_session_state_t state;

	// `code` is the platform's error code or a controller's return; `error` never holds a route or device name.
	long code;
	char error[192];

	// One of the ARMSX3_AUDIO_BRANCH_* literals above.
	char last_branch[64];

	// Each successful create_and_start counts in starts, each stop_and_destroy in stops.
	unsigned long starts;
	unsigned long stops;

	int observers_registered; // both of them, or neither: they are registered together
	int stream_running;       /* the module's belief, which is what the orderings are written against */
	int controller_present;   /* 0 means every call below is a no-op that says so */
} armsx3_audio_session_status_t;

// Sets the category and registers both observers. Idempotent, and does not activate; start() does.
int armsx3_audio_session_configure(void);

// Activate, then start: a stream started against an inactive session is silent with nothing logged.
int armsx3_audio_session_start(void);

// Stop, then deactivate. Returns 0 when the stream is stopped and the session deactivated.
int armsx3_audio_session_stop(void);

// Never partially fills; ignores a NULL out.
void armsx3_audio_session_status(armsx3_audio_session_status_t* out);

// Play-and-record on the speaker with A2DP when on, so audio never moves to the earpiece. Never asks for the mic.
int armsx3_audio_session_set_recording(int on);

#ifdef __cplusplus
} /* extern "C" */
#endif

#endif /* ARMSX3_IOS_AUDIO_SESSION_H */
