# Flint Kotlin prototype UI

`FlintHomeView(context, BuildConfig.IS_TV, callbacks)` is a platform Android View.
Call `render(FlintUiState(...))` on the main thread using the VPN service's actual
state. The UI never changes to connected on a click or on a timer.

The phone layout uses one compact page. TV uses two columns; only TV includes an
explicit focus outline. All actions are regular focusable Android controls and
support D-pad OK. Rendering updates existing views, preserving keyboard focus.

The server picker stays available while connected and reports only supplied probe
results. Values are labelled “TCP … мс” / “Нет ответа TCP”; they describe a port
probe rather than a successful VPN handshake. The picker explains this distinction.
Null availability means “TCP не проверен”, not zero ping or unavailable.
An unavailable entry is grey but remains selectable for a manual retry. Load is
shown only when the supplied percentage is between 0 and 100.

Import actions are clipboard, QR from image, direct text entry, and a file. Each
action delegates to MainActivity; no unimplemented account or purchase buttons
are displayed. The footer identifies the prototype clearly.

Optional resources in `drawable-nodpi`: `flint_background`, `flint_logo`,
`flint_mascot`, `flint_mascot_sad`. The mascot preserves the original composition,
changes only the face region and exterior ring, and processes artwork off the UI
thread. It does not add pre-rendered copies to the APK. State colours are grey,
yellow, green and red for disconnected, connecting, connected and error.

The compact traffic row is hidden until real traffic information is supplied. A
progress bar is displayed only when a finite fraction is known. Error detail is
available by tapping the status description, so long diagnostics are not lost to
the two-line compact label.

Device validation still required: smallest supported phone layout, large system
font, TV overscan, D-pad traversal, system document picker, TalkBack, and service
state recovery after returning to this screen.
