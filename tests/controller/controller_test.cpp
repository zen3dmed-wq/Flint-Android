#include <QtTest>
#include <QTemporaryDir>
#include <QTcpServer>
#include <QTcpSocket>
#include <QRegularExpression>
#include "flintController.h"
#include "flintDirectSites.h"

class ControllerTests : public QObject {
    Q_OBJECT
private slots:
    void identityLinkUsesAuthenticatedRoutesAndRefreshesAfterMigration() {
        QTcpServer server; QVERIFY(server.listen(QHostAddress::LocalHost));
        QStringList paths; QList<QByteArray> requests; int completes = 0;
        connect(&server, &QTcpServer::newConnection, &server, [&]() {
            auto *socket = server.nextPendingConnection();
            connect(socket, &QTcpSocket::readyRead, socket, [&, socket]() {
                auto raw = socket->property("request").toByteArray() + socket->readAll();
                socket->setProperty("request", raw);
                const auto split = raw.indexOf("\r\n\r\n"); if (split < 0) return;
                const QRegularExpression length("Content-Length: (\\d+)", QRegularExpression::CaseInsensitiveOption);
                const auto match = length.match(QString::fromUtf8(raw.left(split)));
                if (match.hasMatch() && raw.size() < split + 4 + match.captured(1).toInt()) return;
                requests.append(raw); const QString path = QString::fromUtf8(raw.split(' ').at(1)); paths << path;
                QByteArray status = "200 OK", body = R"({"id":"same-account","email":"test@example.invalid","telegram":{"id":123}})";
                if (path == "/me/telegram/bot/start") body = R"({"loginId":"test-link","botUrl":"https://t.me/Flintgo_bot?start=test","expiresAt":"2099-01-01T00:00:00Z"})";
                if (path == "/me/telegram/bot/complete" && completes++ == 0) { status = "401 Unauthorized"; body = R"({"code":"unauthorized"})"; }
                if (path == "/auth/refresh") body = R"({"accessToken":"NEW-TEST","refreshToken":"NEW-REFRESH","expiresIn":3600})";
                socket->write("HTTP/1.1 " + status + "\r\nContent-Length: " + QByteArray::number(body.size()) + "\r\nConnection: close\r\n\r\n" + body); socket->disconnectFromHost();
            });
        });
        QTemporaryDir dir; SecureQSettings settings(dir.filePath("settings.ini"), QSettings::IniFormat);
        settings.setValue("Conf/flintStartupSchema",999);
        settings.setValue("Conf/flintApiBase",QString("http://127.0.0.1:%1").arg(server.serverPort()));
        settings.setValue("Conf/flintAccessToken","OLD-TEST"); settings.setValue("Conf/flintRefreshToken","OLD-REFRESH");
        FlintController controller(&settings); QSignalSpy responses(&controller,&FlintController::accountResponse);
        controller.accountRequest("email","POST","/me/email-login",{{"email","test@example.invalid"},{"password","TEST-PASSWORD"}},"");
        QTRY_COMPARE_WITH_TIMEOUT(responses.size(),1,2000);
        QCOMPARE(responses.last().at(1).toInt(),200); QVERIFY(requests.first().contains("Authorization: Bearer OLD-TEST"));
        controller.accountRequest("start","POST","/me/telegram/bot/start",{},"");
        QTRY_COMPARE_WITH_TIMEOUT(responses.size(),2,2000);
        controller.accountRequest("complete","POST","/me/telegram/bot/complete",{{"loginId","test-link"}},"");
        QTRY_COMPARE_WITH_TIMEOUT(responses.size(),3,2000);
        QCOMPARE(responses.last().at(1).toInt(),200); QVERIFY(responses.last().at(3).toString().isEmpty());
        QCOMPARE(completes,2); QVERIFY(paths.contains("/auth/refresh")); QVERIFY(requests.last().contains("Authorization: Bearer NEW-TEST"));
        QCOMPARE(settings.value("Conf/flintRefreshToken").toString(),QString("NEW-REFRESH"));
        for (const QString &path : {"/me/email-login","/me/telegram/bot/start","/me/telegram/bot/complete"}) {
            controller.accountRequest("bad","GET",path,{},""); QCOMPARE(responses.last().at(1).toInt(),400);
        }
    }
    void normalizesDirectSitesWithoutBroadeningInvalidRules() {
        QCOMPARE(FlintDirectSites::normalize(" HTTPS://Example.RU:443/path?q=1 "), QString("example.ru"));
        QCOMPARE(FlintDirectSites::normalize(QString::fromUtf8("пример.рф")), QString("xn--e1afmkfd.xn--p1ai"));
        for (const QString &bad : {"", "*", "*.ru", "https://user:pass@example.ru", "ftp://example.ru", "https://bad host.ru", "-bad.ru", "http://"})
            QVERIFY2(FlintDirectSites::normalize(bad).isEmpty(), qPrintable(bad));
    }
    void stableInstallationIDSurvivesRestartAndLogout() {
        QTemporaryDir dir;
        SecureQSettings settings(dir.filePath("settings.ini"), QSettings::IniFormat);
        settings.setValue("Conf/flintStartupSchema", 999);
        QString id;
        { FlintController first(&settings); id=settings.value("Conf/flintDeviceId").toString(); QVERIFY(!id.isEmpty()); first.logout(); }
        { FlintController second(&settings); QCOMPARE(settings.value("Conf/flintDeviceId").toString(), id); }
    }

    void deviceDeleteAcceptsNoContentAndRejectsOtherDeletes() {
        QTcpServer server;
        QVERIFY(server.listen(QHostAddress::LocalHost));
        connect(&server, &QTcpServer::newConnection, &server, [&server]() {
            auto *socket = server.nextPendingConnection();
            connect(socket, &QTcpSocket::readyRead, socket, [socket]() {
                socket->readAll(); socket->write("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n"); socket->disconnectFromHost();
            });
        });
        QTemporaryDir dir;
        SecureQSettings settings(dir.filePath("settings.ini"), QSettings::IniFormat);
        settings.setValue("Conf/flintStartupSchema", 999);
        settings.setValue("Conf/flintApiBase", QString("http://127.0.0.1:%1").arg(server.serverPort()));
        settings.setValue("Conf/flintRefreshToken", "TEST-ONLY");
        settings.setValue("Conf/flintAccessToken", "TEST-ONLY");
        FlintController controller(&settings);
        QSignalSpy responses(&controller, &FlintController::accountResponse);
        controller.accountRequest("revoke", "DELETE", "/subscriptions/sub1/devices/device1", {}, "");
        QTRY_COMPARE_WITH_TIMEOUT(responses.size(), 1, 2000);
        QCOMPARE(responses.at(0).at(1).toInt(), 204);
        QVERIFY(responses.at(0).at(3).toString().isEmpty());
        controller.accountRequest("bad", "DELETE", "/subscriptions", {}, "");
        QCOMPARE(responses.last().at(1).toInt(), 400);
        controller.accountRequest("session", "DELETE", "/me/sessions/current", {}, "");
        QTRY_COMPARE_WITH_TIMEOUT(responses.size(), 3, 2000);
        QVERIFY(!responses.last().at(3).toString().isEmpty());
    }
    void sessionRevokeChecksInventoryProtectsCurrentAndRequires204() {
        QTcpServer server; QVERIFY(server.listen(QHostAddress::LocalHost));
        int deletes = 0; int deleteStatus = 204;
        connect(&server, &QTcpServer::newConnection, &server, [&]() {
            auto *socket = server.nextPendingConnection();
            connect(socket, &QTcpSocket::readyRead, socket, [&,socket]() {
                const auto request = socket->readAll();
                QByteArray body; QByteArray status = "200 OK";
                if (request.startsWith("GET /me/sessions ")) body = R"({"items":[{"id":"current","isCurrent":true},{"id":"old","isCurrent":false}]})";
                else { ++deletes; status = deleteStatus == 204 ? "204 No Content" : "202 Accepted"; body = deleteStatus == 204 ? "" : "{}"; }
                socket->write("HTTP/1.1 " + status + "\r\nContent-Length: " + QByteArray::number(body.size()) + "\r\nConnection: close\r\n\r\n" + body); socket->disconnectFromHost();
            });
        });
        QTemporaryDir dir; SecureQSettings settings(dir.filePath("settings.ini"), QSettings::IniFormat);
        settings.setValue("Conf/flintStartupSchema", 999);
        settings.setValue("Conf/flintApiBase", QString("http://127.0.0.1:%1").arg(server.serverPort()));
        settings.setValue("Conf/flintRefreshToken", "TEST"); settings.setValue("Conf/flintAccessToken", "TEST");
        FlintController controller(&settings); QSignalSpy responses(&controller, &FlintController::accountResponse);
        for (const QString &id : {"current", "foreign", "old"}) {
            const auto before = responses.size(); controller.accountRequest(id,"DELETE","/me/sessions/"+id,{},"");
            QTRY_COMPARE_WITH_TIMEOUT(responses.size(),before+1,2000);
            QCOMPARE(responses.last().at(1).toInt(), id == "current" ? 400 : id == "foreign" ? 404 : 204);
        }
        QCOMPARE(deletes,1);
        deleteStatus=202; controller.accountRequest("pending","DELETE","/me/sessions/old",{},"");
        QTRY_COMPARE_WITH_TIMEOUT(responses.size(),4,2000);
        QVERIFY(!responses.last().at(3).toString().isEmpty());
    }
    void autoUsesCachedWorkingProfileAndHasBoundedAlternatives() {
        QTemporaryDir dir;
        SecureQSettings settings(dir.filePath("settings.ini"), QSettings::IniFormat);
        settings.setValue("Conf/flintStartupSchema", 999);
        const QString sub = "https://127.0.0.1:1/sub";
        const QString first = "vless://test@example.invalid:443#Germany";
        const QString working = "vless://test@example.invalid:444#Armenia";
        const QString third = "vless://test@example.invalid:445#USA";
        const QString fourth = "vless://test@example.invalid:446#France";
        settings.setValue("Conf/flintSubscriptionUrl", sub);
        settings.setValue("Conf/flintCachedProfilesUrl", sub);
        settings.setValue("Conf/flintCachedProfiles", (first+'\n'+working+'\n'+third+'\n'+fourth).toUtf8());
        settings.setValue("Conf/flintWorkingProfile", working);
        FlintController controller(&settings);
        QSignalSpy ready(&controller, &FlintController::profileReady);
        controller.importSubscription();
        QCOMPARE(ready.size(), 1);
        QCOMPARE(ready.last().first().toString(), working);
        QVERIFY(!controller.busy());
        controller.profileInstallResult(true);
        QVERIFY(!controller.profilePreparing());
        QVERIFY(controller.tryNextAutomaticProfile());
        QCOMPARE(ready.last().first().toString(), first);
        QVERIFY(controller.tryNextAutomaticProfile());
        QCOMPARE(ready.last().first().toString(), third);
        QVERIFY(!controller.tryNextAutomaticProfile());
        settings.setValue("Conf/flintCachedProfilesUrl", "https://another.invalid/sub");
        QVERIFY(!controller.tryNextAutomaticProfile());
    }
    void selectionTimeoutReleasesPreparationAndDoesNotBlockAccount() {
        QTcpServer server;
        QVERIFY(server.listen(QHostAddress::LocalHost));
        connect(&server, &QTcpServer::newConnection, &server, [&server]() {
            auto *socket = server.nextPendingConnection();
            connect(socket, &QTcpSocket::readyRead, socket, [socket]() {
                socket->readAll(); // Accept the request, deliberately never answer.
            });
        });
        QTemporaryDir dir;
        SecureQSettings settings(dir.filePath("settings.ini"), QSettings::IniFormat);
        settings.setValue("Conf/flintStartupSchema", 999);
        settings.setValue("Conf/flintSubscriptionUrl", QString("http://127.0.0.1:%1/sub").arg(server.serverPort()));
        FlintController controller(&settings);
        QSignalSpy finished(&controller, &FlintController::profilePreparationFinished);
        controller.importSubscription();
        QVERIFY(controller.profilePreparing());
        QVERIFY(!controller.busy());
        QTRY_COMPARE_WITH_TIMEOUT(finished.size(), 1, 12000);
        QVERIFY(!finished.first().first().toBool());
        QVERIFY(!controller.profilePreparing());
        QVERIFY(!controller.lastError().contains("timed out"));
    }
    void backgroundRefreshNeverBlocksPreparationOrReportsTimeout() {
        QTemporaryDir dir;
        SecureQSettings settings(dir.filePath("settings.ini"), QSettings::IniFormat);
        settings.setValue("Conf/flintStartupSchema", 999);
        settings.setValue("Conf/flintSubscriptionUrl", "https://127.0.0.1:1/sub");
        FlintController controller(&settings);
        QSignalSpy ready(&controller, &FlintController::profileReady);
        controller.importSubscription(false);
        QVERIFY(!controller.profilePreparing());
        QVERIFY(!controller.busy());
        QTest::qWait(100);
        QVERIFY(controller.lastError().isEmpty());
        QCOMPARE(ready.size(), 0);
    }
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
        controller.importSubscription(true, true);
        QVERIFY(controller.profilePreparing());
        controller.syncSavedServers({QVariantMap{{"id", "manual"}, {"name", "My server"}}}, "manual");
        QTRY_VERIFY_WITH_TIMEOUT(!controller.profilePreparing(), 2000);
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
