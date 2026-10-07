#include <QtTest>
#include "flintTelemetry.h"

class TelemetryTests : public QObject {
    Q_OBJECT
private slots:
    void matchingUsesEndpointNotCountryOrName() {
        QVERIFY(FlintTelemetry::addressMatches("VPN.EXAMPLE.", QUrl("vless://id@vpn.example:8443#Armenia")));
        QVERIFY(FlintTelemetry::addressMatches("vpn.example:8443", QUrl("vless://id@vpn.example:8443")));
        QVERIFY(!FlintTelemetry::addressMatches("vpn.example:443", QUrl("vless://id@vpn.example:8443")));
        QVERIFY(!FlintTelemetry::addressMatches("vpn.example.evil", QUrl("vless://id@vpn.example:443")));
        QVERIFY(FlintTelemetry::addressMatches("2001:db8::1", QUrl("vless://id@[2001:0db8:0:0::1]:443")));
        QVERIFY(FlintTelemetry::addressMatches("[2001:db8::1]:443", QUrl("vless://id@[2001:db8::1]:443")));
        QVERIFY(!FlintTelemetry::addressMatches("https://u:p@vpn.example/path", QUrl("vless://id@vpn.example:443")));
    }
    void zeroNullAndDifferentServers() {
        const qint64 now=1791383512000;
        QJsonObject doc{{"loadUpdatedAt","2026-10-07T14:31:01.7656465Z"},
            {"items",QJsonArray{QJsonObject{{"name","Armenia"},{"addresses",QJsonArray{"one.example"}},{"load",31}},
                               QJsonObject{{"name","Armenia"},{"addresses",QJsonArray{"two.example"}},{"load",0}}}}};
        QCOMPARE(FlintTelemetry::location(doc,"vless://id@one.example:443",now).value("loadPercent").toDouble(),31.0);
        auto zero=FlintTelemetry::location(doc,"vless://id@two.example:443",now);
        QVERIFY(zero.contains("loadPercent")); QCOMPARE(zero.value("loadPercent").toDouble(),0.0);
        QVERIFY(FlintTelemetry::location(doc,"vless://id@other.example:443",now).isEmpty());
        for (const QJsonValue invalid : {QJsonValue(QJsonValue::Null),QJsonValue(-1),QJsonValue(101),QJsonValue("0")}) {
            doc["items"]=QJsonArray{QJsonObject{{"addresses",QJsonArray{"one.example"}},{"load",invalid}}};
            QVERIFY(FlintTelemetry::location(doc,"vless://id@one.example:443",now).isEmpty());
        }
    }
    void staleMissingFutureAndOffline() {
        const qint64 now=1791383512000;
        QJsonObject doc{{"loadUpdatedAt","2026-10-07T14:31:01Z"},
            {"items",QJsonArray{QJsonObject{{"addresses",QJsonArray{"one.example"}},{"status","offline"},{"load",QJsonValue::Null}}}}};
        auto offline=FlintTelemetry::location(doc,"vless://id@one.example:443",now);
        QVERIFY(offline.contains("available")); QVERIFY(!offline.value("available").toBool());
        QVERIFY(!offline.contains("loadPercent"));
        for (const QString bad : {QString(),QString("invalid"),QString("2026-10-07T14:29:00Z"),QString("2026-10-07T14:35:00Z")}) {
            doc["loadUpdatedAt"]=bad; doc["updatedAt"]="2026-10-07T14:31:52Z";
            QVERIFY(FlintTelemetry::location(doc,"vless://id@one.example:443",now).isEmpty());
        }
    }
};
QTEST_GUILESS_MAIN(TelemetryTests)
#include "telemetry_test.moc"
