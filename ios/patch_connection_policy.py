"""Use iOS NetworkExtension policy, never a timer in the visible Qt window."""
from pathlib import Path

def apply(root: Path):
    path=root/'client/platforms/ios/ios_controller.mm'
    text=path.read_text(encoding='utf-8')
    helpers='''
// Flint settings are read when saving the system VPN configuration, which iOS
// owns after the app leaves the foreground. No process must remain visible.
extern "C" bool FlintAutoConnectEnabled() {
    return [[NSUserDefaults standardUserDefaults] boolForKey:@"FlintAutoConnect"];
}
extern "C" bool FlintKillSwitchEnabled() {
    id value=[[NSUserDefaults standardUserDefaults] objectForKey:@"FlintKillSwitch"];
    return value ? [value boolValue] : true;
}
extern "C" void FlintSetConnectionPolicy(bool automatic,bool protection) {
    [[NSUserDefaults standardUserDefaults] setBool:automatic forKey:@"FlintAutoConnect"];
    [[NSUserDefaults standardUserDefaults] setBool:protection forKey:@"FlintKillSwitch"];
}
'''
    assert 'const char* Action::start' in text
    text=text.replace('const char* Action::start',helpers+'\nconst char* Action::start',1)
    anchor='    [tunnel setEnabled:YES];'
    assert anchor in text
    text=text.replace(anchor,anchor+'''
    if (@available(iOS 14.0, *)) {
        tunnelProtocol.includeAllNetworks=FlintKillSwitchEnabled();
        tunnelProtocol.excludeLocalNetworks=NO;
    }
    NEOnDemandRuleConnect *rule=[[NEOnDemandRuleConnect alloc] init];
    rule.interfaceTypeMatch=NEOnDemandRuleInterfaceTypeAny;
    tunnel.onDemandRules=FlintAutoConnectEnabled() ? @[rule] : @[];
    tunnel.onDemandEnabled=FlintAutoConnectEnabled();
''',1)
    old='        [(NETunnelProviderSession *)m_currentTunnel.connection stopTunnel];'
    assert old in text
    text=text.replace(old,'''        // Intentional disconnect must not immediately trigger On Demand again.
        NETunnelProviderManager *tunnel=m_currentTunnel;
        tunnel.onDemandEnabled=NO;
        [tunnel saveToPreferencesWithCompletionHandler:^(NSError *error) {
            if (error) { qWarning() << "Flint: unable to save explicit disconnect"; return; }
            [(NETunnelProviderSession *)tunnel.connection stopTunnel];
        }];''',1)
    path.write_text(text,encoding='utf-8')
    path=root/'client/platforms/ios/PacketTunnelProvider+Xray.swift'
    text=path.read_text(encoding='utf-8')
    old='            applyXraySplitTunnel(xrayConfig, settings: settings)'
    assert old in text
    text=text.replace(old,'''            // Excluded IP routes cannot bypass includeAllNetworks. Russian
            // services are selected by Flint's existing Xray direct rules.
            if !protocolConfiguration.includeAllNetworks {
                applyXraySplitTunnel(xrayConfig, settings: settings)
            }''',1)
    path.write_text(text,encoding='utf-8')
