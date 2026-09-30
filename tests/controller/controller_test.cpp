#include <QtTest>
#include <QTemporaryDir>
#include "flintController.h"

class ControllerTests : public QObject {
    Q_OBJECT
private slots:
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
};
QTEST_GUILESS_MAIN(ControllerTests)
#include "controller_test.moc"
