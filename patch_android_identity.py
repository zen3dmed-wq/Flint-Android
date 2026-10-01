"""Keep Android runtime identity separate from the engine's Java namespace."""
from pathlib import Path
import shutil
import xml.etree.ElementTree as ET


def apply(root: Path, assets: Path):
    android = root / "client/android"
    proto = android / "src/org/amnezia/vpn/VpnProto.kt"
    source = proto.read_text(encoding="utf-8")
    # :service process names in the manifest are relative to applicationId,
    # not to the Java namespace. All five protocols must follow that identity.
    assert source.count('"org.amnezia.vpn:amnezia') == 5
    source = source.replace('"org.amnezia.vpn:amnezia', 'BuildConfig.APPLICATION_ID + ":amnezia')
    proto.write_text(source, encoding="utf-8")

    res = android / "res"
    (res / "drawable-nodpi").mkdir(exist_ok=True)
    # Keep the supplied artwork byte-for-byte; Android sizes it at rendering.
    shutil.copy2(assets / "flint-emblem.jpg", res / "drawable-nodpi/flint_emblem.jpg")
    shutil.copy2(assets / "ic_flint_notification.xml", res / "drawable/ic_flint_notification.xml")
    (res / "mipmap-anydpi-v26/flint_icon.xml").write_text('''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background><shape android:shape="rectangle"><solid android:color="#FFFFFF" /></shape></background>
    <foreground><inset android:inset="14%" android:drawable="@drawable/flint_emblem" /></foreground>
    <monochrome><inset android:inset="20%" android:drawable="@drawable/ic_flint_notification" /></monochrome>
</adaptive-icon>
''', encoding="utf-8")

    manifest = android / "AndroidManifest.xml"
    source = manifest.read_text(encoding="utf-8")
    # Earlier branding only replaced @drawable while the application used
    # @mipmap/icon. Set the application attributes explicitly, independently
    # from service / quick-settings icons.
    import re
    start = source.index("<application")
    end = source.index(">", start)
    application = source[start:end]
    for name in ("icon", "roundIcon"):
        application, count = re.subn(r'android:' + name + r'="[^"]+"',
                                    f'android:{name}="@mipmap/flint_icon"', application)
        assert count == 1, name
    source = source[:start] + application + source[end:]
    source = source.replace('android:icon="@drawable/flint_launcher"',
                            'android:icon="@drawable/ic_flint_notification"')
    source = source.replace('android:icon="@drawable/ic_amnezia_round"',
                            'android:icon="@drawable/ic_flint_notification"')
    ET.fromstring(source)
    manifest.write_text(source, encoding="utf-8")

    notification = android / "src/org/amnezia/vpn/ServiceNotification.kt"
    source = notification.read_text(encoding="utf-8")
    assert '.setSmallIcon(R.drawable.ic_amnezia_round)' in source
    source = source.replace('.setSmallIcon(R.drawable.ic_amnezia_round)',
                            '.setSmallIcon(R.drawable.ic_flint_notification)')
    source = source.replace('serverName ?: "AmneziaVPN"', 'serverName ?: "Flint"')
    notification.write_text(source, encoding="utf-8")
