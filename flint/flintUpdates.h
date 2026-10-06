#pragma once
#include <QObject>
#include <QVariantMap>
#include <QJsonObject>
#include <QPointer>
#include <QNetworkReply>
#include <QNetworkAccessManager>
#include <functional>
class FlintController;
class FlintUpdates : public QObject {
    Q_OBJECT
    Q_PROPERTY(QVariantMap state READ state NOTIFY changed)
public:
    explicit FlintUpdates(FlintController *account, QObject *parent = nullptr);
    QVariantMap state() const { return m_state; }
    Q_INVOKABLE void check(bool manual = true);
    Q_INVOKABLE void download();
    Q_INVOKABLE void cancel();
    Q_INVOKABLE void install();
signals:
    void changed();
    void available();
private:
    void reset();
    void fetch(std::function<void(bool)> done);
    void fail(const QString &message);
    void beginDownload();
    FlintController *m_account;
    QNetworkAccessManager m_network;
    QPointer<QNetworkReply> m_reply;
    QVariantMap m_state;
    QJsonObject m_latest;
    QString m_file, m_announced;
    qint64 m_lastCheck = 0;
    int m_epoch = 0;
};
