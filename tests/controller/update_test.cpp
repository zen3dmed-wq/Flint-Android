#include <QtTest>
#include <QTcpServer>
#include <QTcpSocket>
#include <QTemporaryDir>
#include <QJsonDocument>
#include "flintController.h"
#include "flintUpdates.h"
#include "flintUpdatePolicy.h"

class UpdateTests : public QObject {
    Q_OBJECT
    QString nextVersion() const {
        const auto v = FlintUpdatePolicy::parseVersion(FlintUpdatePolicy::version);
        return QVersionNumber(v.majorVersion(), v.minorVersion(), v.microVersion()+1).toString();
    }
private slots:
    void numericVersionsPreventDowngrades() {
        QVERIFY(FlintUpdatePolicy::newer("8.10.26", "8.10.9"));
        QVERIFY(!FlintUpdatePolicy::newer("8.10.9", "8.10.19"));
        QVERIFY(!FlintUpdatePolicy::newer("8.10.19.0", "8.10.19"));
        QVERIFY(!FlintUpdatePolicy::newer("invalid", "8.10.19"));
        QVERIFY(!FlintUpdatePolicy::newer("9999999999.0", "8.10.19"));
    }
    void validatesDownloadAuthorityAndIntegrityMetadata() {
        const QUrl base("https://flintmain.ru/api/v1");
        QJsonObject release{{"version",nextVersion()},{"versionCode",FlintUpdatePolicy::versionCode+1},{"url","https://flintmain.ru/api/v1/app/files/id/file.apk?sig=test"},{"fileName","file.apk"},{"size",1234},{"sha256",QString(64,'a')}};
        QVERIFY(FlintUpdatePolicy::validate(release,base).isEmpty());
        for (const QString bad : {"http://flintmain.ru/api/v1/app/files/id/file.apk", "https://evil.invalid/api/v1/app/files/id/file.apk", "https://flintmain.ru/api/v1/app/files/../file.apk", "https://user@flintmain.ru/api/v1/app/files/id/file.apk", "https://flintmain.ru:444/api/v1/app/files/id/file.apk"}) {
            auto altered=release; altered["url"]=bad; QVERIFY(!FlintUpdatePolicy::validate(altered,base).isEmpty());
        }
        auto altered=release;altered["sha256"]="bad";QVERIFY(!FlintUpdatePolicy::validate(altered,base).isEmpty());
        altered=release;altered["versionCode"]=2188;QVERIFY(!FlintUpdatePolicy::validate(altered,base).isEmpty());
        altered=release;altered["size"]=double(FlintUpdatePolicy::maxBytes+1);QVERIFY(!FlintUpdatePolicy::validate(altered,base).isEmpty());
        QVERIFY(FlintUpdatePolicy::appleUrl(QUrl("https://testflight.apple.com/j/example")));
        QVERIFY(!FlintUpdatePolicy::appleUrl(QUrl("https://flintmain.ru/file.ipa")));
    }
    void liveNullLatestAndMandatoryUpdateAndLogout() {
        QTemporaryDir dir; SecureQSettings settings(dir.filePath("settings.ini"),QSettings::IniFormat);
        settings.setValue("Conf/flintStartupSchema",999); settings.setValue("Conf/flintAccessToken","test-token"); settings.setValue("Conf/flintRefreshToken","test-refresh");
        QTcpServer server; QVERIFY(server.listen(QHostAddress::LocalHost));
        settings.setValue("Conf/flintApiBase",QString("http://127.0.0.1:%1/api/v1").arg(server.serverPort()));
        QByteArray response=R"({"updateAvailable":false,"required":false,"minVersion":null,"latest":null})";
        QString requested; QByteArray authorization;
        connect(&server,&QTcpServer::newConnection,this,[&] {
            auto socket=server.nextPendingConnection();
            connect(socket,&QTcpSocket::readyRead,socket,[&,socket] {
                const auto request=socket->readAll(); if(!request.contains("\r\n\r\n")) return;
                requested=QString::fromUtf8(request.split('\n').first()); authorization=request;
                socket->write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+QByteArray::number(response.size())+"\r\nConnection: close\r\n\r\n"+response);socket->disconnectFromHost();
            });
            connect(socket,&QTcpSocket::disconnected,socket,&QObject::deleteLater);
        });
        FlintController account(&settings);FlintUpdates updates(&account);updates.check();
        QTRY_COMPARE(updates.state().value("phase").toString(),QString("current"));
        QVERIFY(requested.contains("/app/update?platform=android&version=8.10.26"));
        QVERIFY(authorization.contains("Authorization: Bearer test-token"));
        QVERIFY(!updates.state().value("available").toBool());
        response=QJsonDocument(QJsonObject{{"updateAvailable",true},{"required",true},{"minVersion",nextVersion()},
            {"latest",QJsonObject{{"version",nextVersion()},{"notes","Example"}}}}).toJson(QJsonDocument::Compact);
        updates.check();QTRY_VERIFY(updates.state().value("required").toBool());
        QVERIFY(updates.state().value("available").toBool());
        response=R"({"updateAvailable":true,"required":true,"latest":{"version":"8.10.9"}})";
        updates.check();QTRY_COMPARE(updates.state().value("phase").toString(),QString("current"));
        QVERIFY(!updates.state().value("required").toBool());
        account.logout();QCOMPARE(updates.state().value("phase").toString(),QString("idle"));
        QVERIFY(!updates.state().value("available").toBool());
    }
};
QTEST_GUILESS_MAIN(UpdateTests)
#include "update_test.moc"
