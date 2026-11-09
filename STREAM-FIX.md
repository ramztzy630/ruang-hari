# 09-11d: fix Capacitor Proxy Promise assimilation

Confirmed root cause of the missing native diagnostics response:
`async function nativeMusic(){ return module.music }` returned a Capacitor plugin Proxy. Its get trap generates a function for `then`, so JavaScript Promise resolution calls a phantom plugin.then(resolve, reject) instead of resolving to the plugin. Consequently callers never reach diagnostics, playVideo, status or stop. This is independent of stream transport.

The helper now returns a plain object of explicitly bound forwarding functions. It has no then property. Search previously worked because it accessed bridge.music.search directly instead of returning the Proxy from an async function.

Regression test reproduces the old unresolved promise with a Proxy matching the get trap in the bundled Capacitor runtime. The new helper resolves and dispatches diagnostics, playVideo, status, stop, pause, resume and seek without touching plugin.then. JS syntax also passes. Source includes the regression test under scripts/check-music-proxy.cjs.

Upload all ZIP contents at repository root and rebuild the APK using the included Actions workflow. This removes the confirmed bridge stall; actual Android extraction and playback still require device testing. No success claim about upstream YouTube streams is implied by the JavaScript regression test.
