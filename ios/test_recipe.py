"""Static integration checks for the generated iOS source, before Xcode build."""
import plistlib, sys
from pathlib import Path
root=Path(sys.argv[1])
group='group.app.flint.vpn'
for rel in ['client/ios/app/main.entitlements','client/ios/networkextension/AmneziaVPNNetworkExtension.entitlements']:
    v=plistlib.loads((root/rel).read_bytes())
    assert v['com.apple.developer.networking.networkextension']==['packet-tunnel-provider']
    assert v['com.apple.security.application-groups']==[group]
    assert v['keychain-access-groups']==['$(AppIdentifierPrefix)'+group]
apple=(root/'client/cmake/branding/apple.cmake').read_text()
assert 'org.amnezia.AmneziaVPN' not in apple and 'X7UJ388FXK' not in apple
assert 'app.flint.vpn' in apple
controller=(root/'client/ui/controllers/flintController.cpp').read_text(encoding='utf-8')
assert '"android/"' not in controller and '"ios/"' in controller
assert 'd["platform"] = "ios";' in controller
home=(root/'client/ui/qml/Pages2/PageHome.qml').read_text(encoding='utf-8')
assert 'Flint iOS 8.10.13' in home
assert 'FlintDevices {' in home and 'cancelConnection' in home
assert 'profilePreparationFinished' in controller and 'markProfileConnected' in controller
assert 'FlintDevices.qml' in (root/'client/ui/qml/qml.qrc').read_text(encoding='utf-8')
qrc=(root/'client/ui/qml/qml.qrc').read_text(encoding='utf-8')
assert 'Pages2/FlintMascot.qml' in qrc and 'Assets/flint-main-sad.png' in qrc
assert 'FlintMascot {' in home
assets=Path(__file__).resolve().parents[1]/'flint'
for name in ['flint-main.png','flint-main-sad.png']:
    assert (root/'client/ui/qml/Assets'/name).read_bytes()==(assets/name).read_bytes(),name
devices=(root/'client/ui/qml/Pages2/FlintDevices.qml').read_text(encoding='utf-8')
assert 'ownerUserId' in devices and 'canManageDevices' in devices and '204' in devices
assert 'PageSetupWizardQrReader' in home
for name in ['PacketTunnelProvider.swift','PacketTunnelProvider+Xray.swift','PacketTunnelProvider+WireGuard.swift','PacketTunnelProvider+OpenVPN.swift']:
    assert (root/'client/platforms/ios'/name).is_file(),name
v=plistlib.loads((root/'client/ios/app/Info.plist.in').read_bytes())
assert v['NSAppTransportSecurity']['NSAllowsArbitraryLoads'] is False
assert 'tg' in v['LSApplicationQueriesSchemes']
print('iOS identity, shared API, tunnel sources, App Groups and capabilities checked')
