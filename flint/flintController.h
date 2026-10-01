// Flint Android 8.9.8
#ifndef FLINTCONTROLLER_H
#define FLINTCONTROLLER_H

#include <QObject>
#include <QVariantList>
#include <QNetworkAccessManager>
#include <QNetworkRequest>
#include <QJsonObject>
#include <QTimer>
#include <functional>
#include <QList>
#include "secureQSettings.h"

class FlintController : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool loggedIn READ loggedIn NOTIFY authChanged)
    Q_PROPERTY(QString email READ email NOTIFY authChanged)
    Q_PROPERTY(QString telegramUsername READ telegramUsername NOTIFY authChanged)
    Q_PROPERTY(QString telegramBotUrl READ telegramBotUrl NOTIFY telegramChanged)
    Q_PROPERTY(bool telegramPending READ telegramPending NOTIFY telegramChanged)
    Q_PROPERTY(bool subscriptionActive READ subscriptionActive NOTIFY subscriptionChanged)
    Q_PROPERTY(QString subscriptionUrl READ subscriptionUrl NOTIFY subscriptionChanged)
    Q_PROPERTY(int sessionsCount READ sessionsCount NOTIFY subscriptionChanged)
    Q_PROPERTY(QString selectedCountry READ selectedCountry WRITE setSelectedCountry NOTIFY selectedCountryChanged)
    Q_PROPERTY(QVariantList countries READ countries NOTIFY countriesChanged)
    Q_PROPERTY(bool ruDirectEnabled READ ruDirectEnabled WRITE setRuDirectEnabled NOTIFY ruDirectEnabledChanged)
    Q_PROPERTY(QString assistReply READ assistReply NOTIFY assistChanged)
    Q_PROPERTY(QString assistTitle READ assistTitle NOTIFY assistChanged)
    Q_PROPERTY(bool busy READ busy NOTIFY busyChanged)
    Q_PROPERTY(QString lastError READ lastError NOTIFY lastErrorChanged)
    Q_PROPERTY(bool apiOnline READ apiOnline NOTIFY apiStatusChanged)
    Q_PROPERTY(QString apiBase READ apiBase NOTIFY apiBaseChanged)

public:
    explicit FlintController(SecureQSettings *settings, QObject *parent=nullptr);

    bool loggedIn() const;
    QString email() const { return m_email; }
    QString telegramUsername() const { return m_telegramUsername; }
    QString telegramBotUrl() const { return m_telegramBotUrl; }
    bool telegramPending() const { return !m_telegramLoginId.isEmpty(); }
    bool subscriptionActive() const { return m_subscriptionActive; }
    QString subscriptionUrl() const;
    int sessionsCount() const { return m_sessionsCount; }
    QString selectedCountry() const;
    QVariantList countries() const { return m_countries; }
    bool ruDirectEnabled() const;
    QString assistReply() const { return m_assistReply; }
    QString assistTitle() const { return m_assistTitle; }
    bool busy() const { return m_busy; }
    QString lastError() const { return m_lastError; }
    bool apiOnline() const { return m_apiOnline; }
    QString apiBase() const;
    Q_INVOKABLE bool setApiBase(const QString &base);
    Q_INVOKABLE void requestHomeWidget();
    Q_INVOKABLE QString newRequestKey() const;
    Q_INVOKABLE QVariantMap clientDraft(const QString &name) const;
    Q_INVOKABLE void saveClientDraft(const QString &name, const QVariantMap &value);
    Q_INVOKABLE void accountRequest(const QString &id, const QString &method, const QString &path,
                                    const QVariantMap &body, const QString &idempotencyKey);

public slots:
    void refresh();
    void login(const QString &email, const QString &password);
    void registerAccount(const QString &email, const QString &password);
    void logout();
    void startTelegramLogin();
    void checkTelegramLogin();
    void setSelectedCountry(const QString &value);
    void setRuDirectEnabled(bool enabled);
    void importSubscription();
    void askAssist(const QString &message);

signals:
    void authChanged();
    void telegramChanged();
    void subscriptionChanged();
    void selectedCountryChanged();
    void countriesChanged();
    void ruDirectEnabledChanged();
    void assistChanged();
    void busyChanged();
    void lastErrorChanged();
    void apiStatusChanged();
    void profileReady(const QString &uri);
    void apiBaseChanged();
    void accountResponse(const QString &id, int status, const QVariantMap &data, const QString &error);

private:
    QNetworkRequest apiRequest(const QString &path, bool authorized=false) const;
    QByteArray deviceJson() const;
    QString ensureDeviceId();
    void setBusy(bool value);
    void setError(const QString &value);
    void setAssist(const QString &title, const QString &reply);
    void saveTokens(const QJsonObject &obj);
    void clearAuthState();
    void refreshConfig();
    void refreshAccount();
    void refreshAccessToken(std::function<void(bool)> done);
    void authorizedGet(const QString &path, std::function<void(int,const QByteArray&,const QString&)> done, bool retry=true);
    void postPublic(const QString &path, const QJsonObject &body, std::function<void(int,const QByteArray&,const QString&)> done);
    void accountRequestImpl(const QString &id, const QString &method, const QString &path,
                            const QVariantMap &body, const QString &key, bool retry);
    bool validSubscriptionUrl(const QString &value) const;
    QStringList parseSubscriptionProfiles(const QByteArray &raw) const;
    QString chooseProfile(const QStringList &profiles);
    void updateCountriesFromProfiles(const QStringList &profiles);
    static QString profileName(const QString &uri);
    static QString b64url(const QByteArray &in);

    SecureQSettings *m_settings;
    QNetworkAccessManager m_net;
    QTimer m_tgTimer;
    QVariantList m_countries;
    QString m_email;
    QString m_telegramUsername;
    QString m_telegramBotUrl;
    QString m_telegramLoginId;
    QString m_telegramVerifier;
    bool m_subscriptionActive = false;
    int m_sessionsCount = 0;
    QString m_assistReply;
    QString m_assistTitle = QStringLiteral("Flint Assist");
    bool m_busy = false;
    QString m_lastError;
    bool m_apiOnline = true;
    bool m_refreshInFlight = false;
    QList<std::function<void(bool)>> m_refreshWaiters;
    int m_apiEpoch = 0;
    bool m_telegramCheckInFlight = false;
};

#endif
