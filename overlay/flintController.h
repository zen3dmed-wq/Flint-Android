#ifndef FLINTCONTROLLER_H
#define FLINTCONTROLLER_H
// build 8.9.4-r2

#include <QObject>
#include <QVariantList>
#include <QNetworkAccessManager>
#include <QNetworkRequest>
#include <QTimer>
#include "secureQSettings.h"

class FlintController : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool authenticated READ authenticated NOTIFY authChanged)
    Q_PROPERTY(QString email READ email NOTIFY profileChanged)
    Q_PROPERTY(QString planName READ planName NOTIFY subscriptionChanged)
    Q_PROPERTY(bool subscriptionActive READ subscriptionActive NOTIFY subscriptionChanged)
    Q_PROPERTY(QString subscriptionUrl READ subscriptionUrl NOTIFY subscriptionChanged)
    Q_PROPERTY(int devicesUsed READ devicesUsed NOTIFY devicesChanged)
    Q_PROPERTY(int maxDevices READ maxDevices CONSTANT)
    Q_PROPERTY(bool ruDirectEnabled READ ruDirectEnabled WRITE setRuDirectEnabled NOTIFY ruDirectEnabledChanged)
    Q_PROPERTY(QString telegramBotUrl READ telegramBotUrl NOTIFY telegramChanged)
    Q_PROPERTY(bool busy READ busy NOTIFY busyChanged)
    Q_PROPERTY(QString lastError READ lastError NOTIFY lastErrorChanged)

public:
    explicit FlintController(SecureQSettings *settings, QObject *parent=nullptr);

    bool authenticated() const;
    QString email() const;
    QString planName() const;
    bool subscriptionActive() const;
    QString subscriptionUrl() const;
    int devicesUsed() const;
    int maxDevices() const { return 3; }
    bool ruDirectEnabled() const;
    QString telegramBotUrl() const;
    bool busy() const;
    QString lastError() const;

public slots:
    void login(const QString &email, const QString &password);
    void registerAccount(const QString &email, const QString &password);
    void startTelegramLogin();
    void refreshSession();
    void refreshAll();
    void importSubscription();
    void logout();
    void setRuDirectEnabled(bool enabled);

signals:
    void authChanged();
    void profileChanged();
    void subscriptionChanged();
    void devicesChanged();
    void ruDirectEnabledChanged();
    void telegramChanged();
    void busyChanged();
    void lastErrorChanged();
    void profileReady(const QString &uri);

private:
    QNetworkRequest request(const QString &path, bool auth=true) const;
    QJsonObject deviceObject();
    void acceptTokens(const QJsonObject &o);
    void fetchProfile();
    void fetchSubscriptions();
    void fetchSessions();
    void pollTelegram();
    void setBusy(bool value);
    void setError(const QString &value);
    QString problemText(const QByteArray &raw, int status) const;

    SecureQSettings *m_settings;
    QNetworkAccessManager m_net;
    QTimer m_telegramTimer;
    QString m_accessToken;
    QString m_email;
    QString m_planName;
    QString m_subscriptionUrl;
    QString m_telegramBotUrl;
    QString m_telegramLoginId;
    QString m_telegramVerifier;
    int m_devicesUsed = 0;
    bool m_subscriptionActive = false;
    bool m_busy = false;
    QString m_lastError;
};

#endif
