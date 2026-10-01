#include <QtTest>
#include <QTemporaryDir>
#include "flintController.h"

class ControllerTests : public QObject {
    Q_OBJECT
private slots:
    void importedServersKeepIdentityAndSelection() {
        QTemporaryDir dir;
        SecureQSettings settings(dir.filePath("settings.ini"), QSettings::IniFormat);
        settings.setValue("Conf/flintStartupSchema", 999);
        settings.setValue("Conf/flintProfileServerId", "flint-1");
        const QVariantList servers = {
            QVariantMap{{"id", "flint-1"}, {"name", "Armenia"}},
            QVariantMap{{"id", "clipboard-1"}, {"name", "My server"}},
            QVariantMap{{"id", "qr-1"}, {"name", "My server"}}
        };
        {
            FlintController controller(&settings);
            controller.syncSavedServers(servers, "qr-1");
            QCOMPARE(controller.savedServers().size(), 2);
            QCOMPARE(controller.selectedSavedServerId(), QString("qr-1"));
            QSignalSpy changes(&controller, &FlintController::savedServersChanged);
            controller.syncSavedServers(servers, "clipboard-1");
            QCOMPARE(controller.selectedSavedServerId(), QString("clipboard-1"));
            QCOMPARE(changes.count(), 1);
        }
        // The VPN repository restores the default UUID on startup; the UI must
        // accept it without resetting to AUTO or matching a non-unique name.
        FlintController reopened(&settings);
        reopened.syncSavedServers(servers, "qr-1");
        QCOMPARE(reopened.selectedSavedServerId(), QString("qr-1"));
        QCOMPARE(reopened.savedServers().size(), 2);
        auto replacement = servers;
        replacement[0] = QVariantMap{{"id", "flint-2"}, {"name", "USA"}};
        reopened.setManagedProfileServerId("flint-2");
        reopened.syncSavedServers(replacement, "qr-1");
        QCOMPARE(reopened.savedServers(), servers.mid(1));
        QCOMPARE(reopened.selectedSavedServerId(), QString("qr-1"));
        reopened.syncSavedServers(replacement, "flint-2");
        QVERIFY(reopened.selectedSavedServerId().isEmpty());
    }
    void backgroundSubscriptionDoesNotActivateProfile() {
        QTemporaryDir dir;
        SecureQSettings settings(dir.filePath("settings.ini"), QSettings::IniFormat);
        settings.setValue("Conf/flintStartupSchema", 999);
        const QString uri = "vless://00000000-0000-0000-0000-000000000000@example.invalid:443#Armenia";
        settings.setValue("Conf/flintSubscriptionUrl", uri);
        FlintController controller(&settings);
        controller.syncSavedServers({QVariantMap{{"id", "manual"}, {"name", "My server"}}}, "manual");
        QSignalSpy ready(&controller, &FlintController::profileReady);
        controller.importSubscription(false);
        QCOMPARE(ready.count(), 0);
        QCOMPARE(controller.selectedSavedServerId(), QString("manual"));
        QVERIFY(controller.countries().size() > 1);
        controller.importSubscription();
        QCOMPARE(ready.count(), 1);
        QCOMPARE(ready.at(0).at(0).toString(), uri);
    }
    void manualChoiceCancelsPendingSubscriptionFallback() {
        QTemporaryDir dir;
        SecureQSettings settings(dir.filePath("settings.ini"), QSettings::IniFormat);
        settings.setValue("Conf/flintStartupSchema", 999);
        settings.setValue("Conf/flintSubscriptionUrl", "https://127.0.0.1:1/test-subscription");
        settings.setValue("Conf/flintLastProfile", "vless://00000000-0000-0000-0000-000000000000@example.invalid:443#Armenia");
        FlintController controller(&settings);
        QSignalSpy ready(&controller, &FlintController::profileReady);
        controller.importSubscription();
        QVERIFY(controller.busy());
        controller.syncSavedServers({QVariantMap{{"id", "manual"}, {"name", "My server"}}}, "manual");
        QTRY_VERIFY_WITH_TIMEOUT(!controller.busy(), 2000);
        QTest::qWait(50);
        QCOMPARE(ready.count(), 0);
        QCOMPARE(controller.selectedSavedServerId(), QString("manual"));
        QVERIFY(controller.lastError().isEmpty());
    }
    void rejectUnsafeEndpoints() {
        QTemporaryDir dir;
        SecureQSettings settings(dir.filePath("settings.ini"), QSettings::IniFormat);
        settings.setValue("Conf/flintStartupSchema", 999);
        FlintController controller(&settings);
        const auto initial = controller.apiBase();
        QVERIFY(!controller.setApiBase("http://example.com/api/v1"));
        QVERIFY(!controller.setApiBase("https://user:secret@example.com/api/v1"));
        QVERIFY(!controller.setApiBase("https://example.com/api/v1?token=secret"));
        QCOMPARE(controller.apiBase(), initial);
        QSignalSpy response(&controller, &FlintController::accountResponse);
        controller.accountRequest("bad", "POST", "/admin/settings", {}, "");
        QCOMPARE(response.count(), 1);
        QCOMPARE(response.at(0).at(1).toInt(), 400);
    }
    void changingAuthorityClearsPrivateState() {
        QTemporaryDir dir;
        SecureQSettings settings(dir.filePath("settings.ini"), QSettings::IniFormat);
        settings.setValue("Conf/flintStartupSchema", 999);
        settings.setValue("Conf/flintRefreshToken", "TEST-ONLY");
        settings.setValue("Conf/flintAccessToken", "TEST-ONLY");
        FlintController controller(&settings);
        controller.saveClientDraft("purchase", {{"key", "TEST-IDEMPOTENCY"}});
        QVERIFY(controller.loggedIn());
        // Loopback closed port: no production service is contacted by this test.
        QVERIFY(controller.setApiBase("https://127.0.0.1:1/api/v1/"));
        QCOMPARE(controller.apiBase(), QString("https://127.0.0.1:1/api/v1"));
        QVERIFY(!controller.loggedIn());
        QVERIFY(controller.clientDraft("purchase").isEmpty());
        QVERIFY(settings.value("Conf/flintAccessToken").toString().isEmpty());
    }
    void requestsNeedAuthenticationAndIdempotency() {
        QTemporaryDir dir;
        SecureQSettings settings(dir.filePath("settings.ini"), QSettings::IniFormat);
        FlintController controller(&settings);
        QSignalSpy response(&controller, &FlintController::accountResponse);
        controller.accountRequest("order", "POST", "/orders", {}, "");
        QCOMPARE(response.takeFirst().at(1).toInt(), 400);
        controller.accountRequest("support", "GET", "/support/tickets", {}, "");
        QCOMPARE(response.takeFirst().at(1).toInt(), 401);
    }
    void logoutReleasesBusyLoginState() {
        QTemporaryDir dir;
        SecureQSettings settings(dir.filePath("settings.ini"), QSettings::IniFormat);
        settings.setValue("Conf/flintApiBase", "https://127.0.0.1:1/api/v1");
        FlintController controller(&settings);
        controller.login("test@example.invalid", "TEST-PASSWORD");
        QVERIFY(controller.busy());
        controller.logout();
        QVERIFY(!controller.busy());
        QVERIFY(!controller.loggedIn());
    }
};
QTEST_GUILESS_MAIN(ControllerTests)
#include "controller_test.moc"
