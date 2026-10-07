# Flint 8.10.20 / 2190

Settings now open Invite a friend directly, without account tabs. Purchase and
support open their own content from Home; subscription switching stays on Home.
The API address/status and manual account refresh/profile preparation are removed
from ordinary settings. Account identity linking remains in the account window.
Login resumes the requested section, and cancelling login clears that intent.

Android has a one-cell 48dp VPN toggle widget with the existing Flint icon.
The widget re-reads service state on tap, preserves VPN permission flow, opens
Flint if no saved configuration exists, and suppresses taps during transitions.
System confirmation is required to pin a widget. Launcher cell sizes vary; an
existing large widget may need removing and adding again after the update.

Shared QML applies to Android phones, Android/Google TV and iOS. Widget pinning
is shown only on Android phones. Windows has the same isolated service dialogs.
Production website, API configuration and release publication are unchanged.
iOS still requires Apple Developer signing before distribution.
