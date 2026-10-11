# Native UI port

Geometry, palette, labels and flows follow the latest Qt flint/ screens.
FlintHomeView renders supplied service/account state and never synthesizes a
successful connection. Phone is a compact single page; landscape/TV is two columns.
TV alone gets explicit focus borders. Native view instances survive render calls
to preserve D-pad focus. Original Qt artwork is composited on a worker at source
resolution, with a 300 ms fade between expressions/states.

Account, subscriptions, purchase, invitation, devices, support and routing remain
separate dark panels. Settings do not duplicate purchase/subscription/support tabs.
Device QR is above account sessions. Plan cards show prices and savings together.
The main subscription selection remains visible after activity reentry.

Port pings are not proof of a VPN handshake. Autotuning tests HTTPS through Xray.
The service verifies a response carried by its Android VPN network before showing
green. Load needs unambiguous endpoint matching and fresh measurement timestamps;
missing load is not converted to zero. Manual selection is never auto-balanced.

Debug UI/API fixtures generate screenshots and check actual control callbacks;
they are excluded from release APKs. Physical phone/font-scale/TV and launcher
checks remain necessary before describing the port as visually/functionally 1:1.
