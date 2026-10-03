#include <QString>
#import <Foundation/Foundation.h>
#import <CoreImage/CoreImage.h>
#import <ImageIO/ImageIO.h>

QString flintReadQrImageIOS(const QString &value, QString &error)
{
    @autoreleasepool {
        NSURL *url = [NSURL URLWithString:[NSString stringWithUTF8String:value.toUtf8().constData()]];
        if (!url.isFileURL) { error = QStringLiteral("Выберите изображение на устройстве."); return {}; }
        const BOOL scoped = [url startAccessingSecurityScopedResource];
        CGImageSourceRef source = nullptr;
        CGImageRef thumbnail = nullptr;
        @try {
            source = CGImageSourceCreateWithURL((CFURLRef)url, nullptr);
            if (!source) { error = QStringLiteral("Не удалось открыть изображение."); return {}; }
            NSDictionary *options = @{(NSString *)kCGImageSourceCreateThumbnailFromImageAlways:@YES,
                (NSString *)kCGImageSourceCreateThumbnailWithTransform:@YES,
                (NSString *)kCGImageSourceThumbnailMaxPixelSize:@2048};
            thumbnail = CGImageSourceCreateThumbnailAtIndex(source, 0, (CFDictionaryRef)options);
            if (!thumbnail) { error = QStringLiteral("Не удалось прочитать изображение."); return {}; }
            CIImage *image = [CIImage imageWithCGImage:thumbnail];
            CIDetector *detector = [CIDetector detectorOfType:CIDetectorTypeQRCode context:nil options:@{CIDetectorAccuracy:CIDetectorAccuracyHigh}];
            NSMutableSet *values = [NSMutableSet set];
            for (CIFeature *feature in [detector featuresInImage:image]) {
                if ([feature isKindOfClass:[CIQRCodeFeature class]]) {
                    NSString *text = ((CIQRCodeFeature *)feature).messageString;
                    if (text.length) [values addObject:text];
                }
            }
            if (values.count == 1) return QString::fromUtf8([[values anyObject] UTF8String]);
            error = values.count > 1 ? QStringLiteral("На изображении несколько QR-кодов. Обрежьте картинку, оставив один.")
                                    : QStringLiteral("На изображении не найден QR-код. Выберите более чёткую картинку.");
        } @catch (NSException *exception) {
            (void)exception;
            error = QStringLiteral("Не удалось прочитать QR-код. Выберите другую картинку.");
        } @finally {
            if (thumbnail) CGImageRelease(thumbnail);
            if (source) CFRelease(source);
            if (scoped) [url stopAccessingSecurityScopedResource];
        }
        return {};
    }
}
