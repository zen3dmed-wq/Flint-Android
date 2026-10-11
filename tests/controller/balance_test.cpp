#include <QtTest>
#include "flintBalance.h"

class BalanceTests:public QObject {
 Q_OBJECT
private slots:
 void failureBypassesLoadCooldown() {
    const qint64 now=1000000;
    QVariantMap a{{"available",false},{"checkedAt",now}};
    QVariantMap b{{"available",true},{"checkedAt",now},{"latencyMs",80},{"loadPercent",95},{"loadAt",now}};
    QVariantMap h{{"a",a},{"b",b},{"unknown",QVariantMap{{"checkedAt",now}}}};
    FlintBalance::State s;
    QVERIFY(s.choose({"a","b","unknown"},h,"a",true,now-1000,now).isEmpty());
    QVERIFY(s.choose({"a","b","unknown"},h,"a",true,now-1000,now).isEmpty());
    a["checkedAt"]=now+15000;h["a"]=a;
    QCOMPARE(s.choose({"a","b","unknown"},h,"a",true,now-1000,now+15000),QString("b"));
    QVERIFY(s.choose({"a","b"},h,"a",false,now-1000,now+15000).isEmpty());
 }
 void scenarios() {
    const qint64 now=1000000;
    auto node=[&](double load,int ping=50,bool available=true) {return QVariantMap{{"available",available},{"latencyMs",ping},{"checkedAt",now},{"loadPercent",load},{"loadAt",now}};};
    QVariantMap h{{"a",node(95)},{"b",node(20,100)},{"c",node(5,10,false)}};
    FlintBalance::State s;
    auto choose=[&](bool enabled=true,qint64 time=1000000) {return s.choose({"a","b","c"},h,"a",enabled,now-200000,time);};
    QVERIFY(choose().isEmpty());QVERIFY(choose().isEmpty());QCOMPARE(s.badSamples,1);
    for(int i=1;i<=2;i++) {auto a=h["a"].toMap();a["loadAt"]=now+i;h["a"]=a;
        QCOMPARE(choose(true,now+i),i==2?QString("b"):QString());}
    QVERIFY(choose(false).isEmpty());QCOMPARE(s.badSamples,0); // manual/disconnected
    QVERIFY(s.choose({"a","b"},h,"a",true,now-1000,now).isEmpty()); // cooldown
    h["a"]=node(95);auto b=node(20);b["loadAt"]=now-120001;h["b"]=b;s.reset();
    for(int i=0;i<4;i++){auto a=node(95);a["loadAt"]=now+i;h["a"]=a;QVERIFY(choose(true,now+i).isEmpty());}
    h["b"]=node(20);s.reset();
    for(int i=0;i<3;i++){auto a=node(95);a["loadAt"]=now+i;h["a"]=a;auto bb=node(20);bb["node"]="same";a["node"]="same";h["a"]=a;h["b"]=bb;QVERIFY(choose(true,now+i).isEmpty());}
    h["b"]=node(20);s.reset();
    for(int i=0;i<2;i++){auto a=node(0,50,false);a["checkedAt"]=now+i;h["a"]=a;QCOMPARE(choose(true,now+i),i==1?QString("b"):QString());}
    h["a"]=node(95);s.reset();QVERIFY(choose().isEmpty());
    h["a"]=node(30);QVERIFY(choose().isEmpty());QCOMPARE(s.badSamples,0); // recovery
    auto stale=node(95);stale["loadAt"]=now-120001;h["a"]=stale;QVERIFY(choose().isEmpty());
    stale["loadAt"]=now+31000;h["a"]=stale;QVERIFY(choose().isEmpty());
    QCOMPARE(FlintBalance::score(QVariantMap{{"available",true},{"checkedAt",now-120001}},now),10000.0);
 }
};
QTEST_GUILESS_MAIN(BalanceTests)
#include "balance_test.moc"
