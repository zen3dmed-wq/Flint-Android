// Flint Android 8.9.8
#ifndef FLINTCONTROLLER_H
#define FLINTCONTROLLER_H

#include <QObject>
#include "flintBalance.h"
#include "flintPairingCodec.h"
#include <QVariantList>
#include <QNetworkAccessManager>
#include <QNetworkRequest>
#include <QJsonObject>
#include <QJsonArray>
#include <QTimer>
#include <QPointer>
#include <QNetworkReply>
#include <functional>
#include <QList>
#include "secureQSettings.h"

class FlintController : public QObject
{
    Q_OBJECT
    friend class FlintUpdates;
    Q_PROPERTY(qulonglong receivedBytes MEMBER m_receivedBytes NOTIFY trafficChanged)
    Q_PROPERTY(qulonglong sentBytes MEMBER m_sentBytes NOTIFY trafficChanged)
    Q_PROPERTY(bool autoConnectEnabled READ autoConnectEnabled WRITE setAutoConnectEnabled NOTIFY connectionPolicyChanged)
    Q_PROPERTY(bool killSwitchEnabled READ killSwitchEnabled WRITE setKillSwitchEnabled NOTIFY connectionPolicyChanged)
    Q_PROPERTY(bool loggedIn READ loggedIn NOTIFY authChanged)
    Q_PROPERTY(QString email READ email NOTIFY authChanged)
    Q_PROPERTY(QString telegramUsername READ telegramUsername NOTIFY authChanged)
    Q_PROPERTY(QString telegramBotUrl READ telegramBotUrl NOTIFY telegramChanged)
    Q_PROPERTY(bool telegramPending READ telegramPending NOTIFY telegramChanged)
    Q_PROPERTY(bool subscriptionActive READ subscriptionActive NOTIFY subscriptionChanged)
    Q_PROPERTY(QString subscriptionUrl READ subscriptionUrl NOTIFY subscriptionChanged)
    Q_PROPERTY(QVariantList subscriptions READ subscriptions NOTIFY subscriptionChanged)
    Q_PROPERTY(QString selectedSubscriptionId READ selectedSubscriptionId NOTIFY subscriptionChanged)
    Q_PROPERTY(QVariantMap selectedSubscription READ selectedSubscription NOTIFY subscriptionChanged)
    Q_PROPERTY(int sessionsCount READ sessionsCount NOTIFY subscriptionChanged)
    Q_PROPERTY(QString selectedCountry READ selectedCountry WRITE setSelectedCountry NOTIFY selectedCountryChanged)
    Q_PROPERTY(QVariantList countries READ countries NOTIFY countriesChanged)
    Q_PROPERTY(QVariantList savedServers READ savedServers NOTIFY savedServersChanged)
    Q_PROPERTY(QString selectedSavedServerId READ selectedSavedServerId NOTIFY savedServersChanged)
    Q_PROPERTY(int healthRevision MEMBER m_healthRevision NOTIFY healthChanged)
    Q_PROPERTY(bool healthBusy MEMBER m_healthBusy NOTIFY healthChanged)
    Q_PROPERTY(QString routingSummary READ routingSummary NOTIFY routingChanged)
    Q_PROPERTY(bool automaticRoutingEnabled READ automaticRoutingEnabled WRITE setAutomaticRoutingEnabled NOTIFY routingChanged)
    Q_PROPERTY(bool ruDirectEnabled READ ruDirectEnabled WRITE setRuDirectEnabled NOTIFY ruDirectEnabledChanged)
    Q_PROPERTY(QString assistReply READ assistReply NOTIFY assistChanged)
    Q_PROPERTY(QString assistTitle READ assistTitle NOTIFY assistChanged)
    Q_PROPERTY(bool busy READ busy NOTIFY busyChanged)
    Q_PROPERTY(bool profilePreparing READ profilePreparing NOTIFY profilePreparingChanged)
    Q_PROPERTY(QString lastError READ lastError NOTIFY lastErrorChanged)
    Q_PROPERTY(bool apiOnline READ apiOnline NOTIFY apiStatusChanged)
    Q_PROPERTY(QString apiBase READ apiBase NOTIFY apiBaseChanged)

public:
    explicit FlintController(SecureQSettings *settings, QObject *parent=nullptr);

    bool autoConnectEnabled() const;
    bool killSwitchEnabled() const;
    void setAutoConnectEnabled(bool enabled);
    void setKillSwitchEnabled(bool enabled);
    Q_INVOKABLE void refreshServers();
    Q_INVOKABLE QVariantMap newPairingState() const { return FlintPairing::state(); }
    Q_INVOKABLE QVariantMap parsePairingLink(const QString &link) const { return FlintPairing::parse(link); }
    Q_INVOKABLE QVariantMap sealPairing(const QString &id,const QString &key,const QVariantMap &payload) const { return FlintPairing::seal(id,key,payload); }
    Q_INVOKABLE QVariantMap openPairing(const QString &id,const QString &key,const QVariantMap &envelope) const { return FlintPairing::open(id,key,envelope); }
    Q_INVOKABLE QVariantMap pairingDevice() { ensureDeviceId(); return QJsonDocument::fromJson(deviceJson()).object().toVariantMap(); }
    Q_INVOKABLE void preparePairing(const QString &requestId,const QString &subscriptionId);
    Q_INVOKABLE bool acceptPairing(const QVariantMap &payload);
    Q_INVOKABLE QString takePairingLink() { auto s=m_pairingLink;m_pairingLink.clear();return s; }
    Q_INVOKABLE void handlePairingUrl(const QUrl &url);
    Q_INVOKABLE void handleHttpsUrl(const QUrl &url);
    bool eventFilter(QObject *object,QEvent *event) override;

    bool loggedIn() const;
    QString email() const { return m_email; }
    QString telegramUsername() const { return m_telegramUsername; }
    QString telegramBotUrl() const { return m_telegramBotUrl; }
    bool telegramPending() const { return !m_telegramLoginId.isEmpty(); }
    bool subscriptionActive() const { return m_subscriptionActive; }
    QString subscriptionUrl() const;
    QVariantList subscriptions() const { return m_subscriptions; }
    QString selectedSubscriptionId() const;
    QVariantMap selectedSubscription() const;
    Q_INVOKABLE QString serverHealthText(const QString &key) const;
    Q_INVOKABLE bool serverUnavailable(const QString &key) const;
    Q_INVOKABLE void prepareExternalImport(const QString &url);
    Q_INVOKABLE void commitExternalImport();
    Q_INVOKABLE void refreshServerHealth();
    Q_INVOKABLE void initializeServers();
    Q_INVOKABLE bool initializeRussianRouting();
    Q_INVOKABLE bool selectSubscription(const QString &id);
    void setVpnActive(bool active) { m_vpnActive = active; if (!active) { m_tunnelConnected=false; m_balance.reset(); } }
    void setVpnConnected(bool connected) { if (!connected) m_tunnelConnected=false; }
    void updateTraffic(quint64 received, quint64 sent) { m_receivedBytes = received; m_sentBytes = sent; emit trafficChanged(); }
    int sessionsCount() const { return m_sessionsCount; }
    QString selectedCountry() const;
    QVariantList countries() const { return m_countries; }
    QVariantList savedServers() const;
    QString selectedSavedServerId() const;
    void syncSavedServers(const QVariantList &servers, const QString &defaultServerId);
    void setManagedProfileServerId(const QString &serverId);
    Q_INVOKABLE void cancelProfileImport();
    bool profilePreparing() const { return m_profilePreparing; }
    void profileInstallResult(bool success);
    void markProfileConnected();
    Q_INVOKABLE bool tryNextAutomaticProfile();
    QString routingSummary() const;
    bool ruDirectEnabled() const;
    QString assistReply() const { return m_assistReply; }
    QString assistTitle() const { return m_assistTitle; }
    bool busy() const { return m_busy; }
    QString lastError() const { return m_lastError; }
    bool apiOnline() const { return m_apiOnline; }
    QString apiBase() const;
    Q_INVOKABLE bool setApiBase(const QString &base);
    Q_INVOKABLE void requestHomeWidget();
    Q_INVOKABLE void resetVpnDiagnostics();
    Q_INVOKABLE QString vpnDiagnostics() const;
    Q_INVOKABLE QString vpnDiagnosticStage() const;
    Q_INVOKABLE QString vpnFailureMessage() const;
    Q_INVOKABLE void decodeQrImage(const QString &url, const QString &requestId);
    bool automaticRoutingEnabled() const;
    void setAutomaticRoutingEnabled(bool enabled);
    Q_INVOKABLE QString normalizeDirectSite(const QString &value) const;
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
    void importSubscription(bool selectProfile = true, bool forceRefresh = false);
    void askAssist(const QString &message);

signals:
    void pairingPrepared(const QString &requestId,const QVariantMap &payload,const QString &error);
    void pairingLinkReceived();
    void connectionPolicyChanged();
    void externalImportPrepared(int count, const QString &error);
    void manualProfilesReady(const QStringList &profiles);
    void manualImportFinished(int count, const QString &error);
    void healthChanged();
    void initializationFinished();
    void automaticReconnectRequested();
    void routingChanged();
    void trafficChanged();
    void vpnPermissionDenied();
    void authChanged();
    void telegramChanged();
    void subscriptionChanged();
    void selectedCountryChanged();
    void countriesChanged();
    void savedServersChanged();
    void ruDirectEnabledChanged();
    void assistChanged();
    void busyChanged();
    void lastErrorChanged();
    void apiStatusChanged();
    void profileReady(const QString &uri);
    void profilePreparingChanged();
    void profilePreparationFinished(bool success);
    void apiBaseChanged();
    void accountResponse(const QString &id, int status, const QVariantMap &data, const QString &error);
    void qrImageDecoded(const QString &requestId, const QString &text, const QString &error);

private:
    QString m_pairingLink;
    bool m_russianRoutingInitialized = false;
    void runServerHealth(bool initialize);
    QString healthKey(const QString &key) const;
    void loadServerTelemetry(bool legacy = false);
    QStringList healthProfiles() const;
    void evaluateAutomaticSwitch();
    QString bestHealthyProfile(const QStringList &profiles) const;
    QString withWorkingFingerprint(const QString &profile) const;
    QVariantMap m_health;
    bool m_healthBusy = false;
    int m_healthRevision = 0;
    FlintBalance::State m_balance;
    QTimer m_balanceTimer;
    bool m_tunnelConnected = false;
    QString m_autoSwitchProfile;
    qint64 m_connectedAt = 0;
    QString m_pendingBaseProfile;
    quint64 m_receivedBytes = 0, m_sentBytes = 0;
    QVariantList m_subscriptions;
    bool m_vpnActive = false;
    bool m_qrImageBusy = false;
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
    void applySubscriptions(const QJsonArray &items);
    void refreshAccessToken(std::function<void(bool)> done);
    void authorizedGet(const QString &path, std::function<void(int,const QByteArray&,const QString&)> done, bool retry=true);
    void postPublic(const QString &path, const QJsonObject &body, std::function<void(int,const QByteArray&,const QString&)> done);
    void accountRequestImpl(const QString &id, const QString &method, const QString &path,
                            const QVariantMap &body, const QString &key, bool retry);
    bool validSubscriptionUrl(const QString &value) const;
    QStringList parseSubscriptionProfiles(const QByteArray &raw) const;
    QString chooseProfile(const QStringList &profiles);
    QStringList cachedProfiles() const;
    void publishProfile(const QString &profile);
    void setProfilePreparing(bool preparing);
    void updateCountriesFromProfiles(const QStringList &profiles);
    static QString profileName(const QString &uri);
    static QString b64url(const QByteArray &in);

    SecureQSettings *m_settings;
    QNetworkAccessManager m_net;
    QTimer m_tgTimer;
    QVariantList m_countries;
    QVariantList m_savedServers;
    QString m_defaultServerId;
    int m_profileEpoch = 0;
    QPointer<QObject> m_profileReply;
    QStringList m_pendingManualProfiles;
    QPointer<QObject> m_externalImport;
    int m_externalImportEpoch=0;
    bool m_profilePreparing = false;
    QStringList m_attemptedProfiles;
    QString m_pendingProfile;
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
