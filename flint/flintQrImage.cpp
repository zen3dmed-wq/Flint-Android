#include "flintController.h"
#include <QCoreApplication>
#include <QJsonDocument>
#include <QJsonObject>
#include <QPointer>
#include <QThread>
#include <QUrl>
#ifdef Q_OS_ANDROID
#include <QJniObject>
#include <QJniEnvironment>
#endif
#ifdef Q_OS_IOS
QString flintReadQrImageIOS(const QString &url, QString &error);
#endif

void FlintController::decodeQrImage(const QString &url, const QString &requestId)
{
    const QUrl source(url);
    if (m_qrImageBusy || requestId.isEmpty() || (!source.isLocalFile() && source.scheme() != "content")) {
        emit qrImageDecoded(requestId, {}, QStringLiteral("Выберите изображение на устройстве и дождитесь распознавания."));
        return;
    }
    m_qrImageBusy = true;
    const QPointer<FlintController> receiver(this);
    auto *worker = QThread::create([receiver, url, requestId]() {
        QString text, error;
#ifdef Q_OS_ANDROID
        const auto context = QNativeInterface::QAndroidApplication::context();
        const auto value = QJniObject::fromString(url);
        auto result = QJniObject::callStaticObjectMethod(
            "org/amnezia/vpn/FlintQrImage", "decode",
            "(Landroid/content/Context;Ljava/lang/String;)Ljava/lang/String;",
            context.object(), value.object<jstring>());
        QJniEnvironment env;
        if (env->ExceptionCheck()) { env->ExceptionClear(); error = QStringLiteral("Не удалось открыть изображение."); }
        else {
            const auto data = QJsonDocument::fromJson(result.toString().toUtf8()).object();
            text = data.value("text").toString(); error = data.value("error").toString();
        }
#elif defined(Q_OS_IOS)
        text = flintReadQrImageIOS(url, error);
#else
        error = QStringLiteral("Распознавание изображения недоступно на этой платформе.");
#endif
        if (text.isEmpty() && error.isEmpty()) error = QStringLiteral("На изображении не найден QR-код. Выберите более чёткую картинку.");
        if (text.size() > 16384) { text.clear(); error = QStringLiteral("QR-код содержит слишком много данных."); }
        if (receiver) QMetaObject::invokeMethod(receiver, [receiver, requestId, text, error]() {
            if (!receiver) return;
            receiver->m_qrImageBusy = false;
            emit receiver->qrImageDecoded(requestId, text, error);
        }, Qt::QueuedConnection);
    });
    connect(worker, &QThread::finished, worker, &QObject::deleteLater);
    worker->start();
}
