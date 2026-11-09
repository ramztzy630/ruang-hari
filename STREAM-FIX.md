# Native bridge error handling — 09-11c

Fix confirmed in source: JS catch awaited native stop before showing the error. A nonresponsive native stop could therefore hide the resolver deadline indefinitely. Error display now happens before fire-and-forget cleanup. Module import and playback are both inside the deadline race; status polling has its own timeout.

Java playVideo initialization is moved off the shared Capacitor plugin thread. Throwable during initialization (including missing runtime classes and initializer errors) is logged and returned to JS instead of leaving a call unresolved. A diagnostics method identifies the native plugin revision, detecting stale native code in a rebuilt UI.

Tests executed: JS syntax; VM simulation confirms timeout text is visible when playVideo, status and stop never resolve; native initialization rejection text is visible. Android compilation and real YouTube playback were not verified in this environment. No claim that a timeout fix alone repairs upstream stream availability.

Upload the entire extracted contents to repository root, including .github, native and scripts. Run the included Actions workflow and update the installed APK. The player must show Native 09-11c connected or an explicit mismatch/error. Capture the exact displayed error if extraction fails.
