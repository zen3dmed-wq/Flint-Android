"""One Android protocol instance; permission refusal cancels the whole attempt."""
from pathlib import Path

def apply(root: Path):
    def edit(name, old, new):
        p = root / name
        text = p.read_text(encoding='utf-8')
        assert old in text, (name, old[:80])
        p.write_text(text.replace(old, new, 1), encoding='utf-8')
    edit('client/vpnConnection.cpp', '''    androidVpnProtocol = createDefaultAndroidVpnProtocol();
    createAndroidConnections();''', '''    // Retire the previous observer before starting another attempt.
    m_vpnProtocol.reset();
    createAndroidConnections();''')
    edit('client/vpnConnection.cpp', '''void VpnConnection::restoreConnection()
{
    createAndroidConnections();''', '''void VpnConnection::restoreConnection()
{
    if (m_vpnProtocol) return;
    createAndroidConnections();''')
    # Denial must also update core state, even before a service is bound. Notify
    # QML first so AUTO does not interpret a user cancellation as a server failure.
    edit('client/core/controllers/coreSignalHandlers.cpp', '''    connect(AndroidController::instance(), &AndroidController::initConnectionState, this, [this](Vpn::ConnectionState state) {''', '''    connect(AndroidController::instance(), &AndroidController::vpnPermissionRejected, this, [this]() {
        emit m_coreController->m_flintController->vpnPermissionDenied();
        m_coreController->m_connectionController->setConnectionState(Vpn::ConnectionState::Disconnected);
    }, Qt::DirectConnection);
    connect(AndroidController::instance(), &AndroidController::initConnectionState, this, [this](Vpn::ConnectionState state) {''')
    # JNI emits on the UI thread; explicit queued delivery avoids changing QML
    # from the Android thread. Install the cancellation handler before native
    # Disconnected reaches the Qt protocol's queued notifications.
    edit('client/core/controllers/coreSignalHandlers.cpp', '    }, Qt::DirectConnection);\n    connect(AndroidController::instance(), &AndroidController::initConnectionState', '    }, Qt::QueuedConnection);\n    connect(AndroidController::instance(), &AndroidController::initConnectionState')
    edit('client/core/controllers/coreSignalHandlers.cpp', '#include "coreSignalHandlers.h"', '#include "coreSignalHandlers.h"\n#include "ui/controllers/flintController.h"')
    edit('client/core/controllers/coreSignalHandlers.cpp',
         'm_coreController->m_connectionUiController->onConnectionStateChanged(state);\n        m_coreController->m_connectionController->restoreConnection();',
         'm_coreController->m_connectionController->setConnectionState(state);\n        m_coreController->m_connectionController->restoreConnection();')
    edit('client/core/controllers/connectionController.cpp', '''void ConnectionController::setConnectionState(Vpn::ConnectionState state)
{
    emit connectionStateChanged(state);
}''', '''void ConnectionController::setConnectionState(Vpn::ConnectionState state)
{
    if (!m_vpnConnection) return;
    const auto connection = m_vpnConnection;
    QMetaObject::invokeMethod(connection, [connection, state]() {
        connection->setConnectionState(state);
    }, Qt::QueuedConnection);
}''')
    edit('client/android/src/org/amnezia/vpn/AmneziaActivity.kt', '''                    showOnVpnPermissionRejectDialog()
                    mainScope.launch {
                        qtInitialized.await()
                        QtAndroidController.onVpnPermissionRejected()
                    }''', '''                    mainScope.launch {
                        qtInitialized.await()
                        QtAndroidController.onVpnPermissionRejected()
                        VpnStateStore.store { VpnState.defaultState }
                    }
                    showOnVpnPermissionRejectDialog()''')
    # No service exists after a rejected permission dialog: don't wait forever
    # for a disconnect reply from a service that never started.
    edit('client/android/src/org/amnezia/vpn/AmneziaActivity.kt', '''        vpnServiceMessenger.send(Action.DISCONNECT)
    }''', '''        if (isServiceConnected) vpnServiceMessenger.send(Action.DISCONNECT)
        else QtAndroidController.onVpnStateChanged(0) // ProtocolState.DISCONNECTED
    }''')
    edit('client/android/src/org/amnezia/vpn/AmneziaActivity.kt',
         '    fun start(vpnConfig: String) {',
         '''    fun isFlintVpnPermissionGranted(): Boolean =
        try { VpnService.prepare(applicationContext) == null } catch (_: Exception) { false }

    @Suppress("unused")
    fun start(vpnConfig: String) {''')
    edit('client/platforms/android/android_controller.cpp',
         'case AndroidController::ConnectionState::CONNECTED: return Vpn::ConnectionState::Connected;',
         '''case AndroidController::ConnectionState::CONNECTED:
            // Cached/native events cannot report protection after consent was denied/revoked.
            return callActivityMethod<jboolean>("isFlintVpnPermissionGranted", "()Z")
                ? Vpn::ConnectionState::Connected : Vpn::ConnectionState::Disconnected;''')
