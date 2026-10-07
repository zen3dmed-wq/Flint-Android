#include <QCoreApplication>
#include <QTemporaryDir>
#include <QUrl>
#include <QDebug>
#import <Foundation/Foundation.h>
#import <CoreImage/CoreImage.h>
#import <ImageIO/ImageIO.h>
QString flintReadQrImageIOS(const QString &, QString &);

static CIImage *qr(NSString *text) {
    CIFilter *filter = [CIFilter filterWithName:@"CIQRCodeGenerator"];
    [filter setValue:[text dataUsingEncoding:NSUTF8StringEncoding] forKey:@"inputMessage"];
    [filter setValue:@"M" forKey:@"inputCorrectionLevel"];
    return [filter.outputImage imageByApplyingTransform:CGAffineTransformMakeScale(8,8)];
}
static bool save(CIImage *image, const QString &path) {
    CGRect extent = CGRectInset(image.extent,-40,-40);
    CIImage *white = [[CIImage imageWithColor:[CIColor colorWithRed:1 green:1 blue:1]] imageByCroppingToRect:extent];
    image = [image imageByCompositingOverImage:white];
    CGImageRef bitmap = [[CIContext contextWithOptions:nil] createCGImage:image fromRect:extent];
    NSURL *url = [NSURL fileURLWithPath:[NSString stringWithUTF8String:path.toUtf8().constData()]];
    CGImageDestinationRef destination = CGImageDestinationCreateWithURL((CFURLRef)url,CFSTR("public.png"),1,nullptr);
    if (!bitmap || !destination) { if (bitmap) CGImageRelease(bitmap); if (destination) CFRelease(destination); return false; }
    CGImageDestinationAddImage(destination,bitmap,nullptr);
    bool ok = CGImageDestinationFinalize(destination);
    CFRelease(destination); CGImageRelease(bitmap); return ok;
}
int main(int argc,char **argv) {
    QCoreApplication app(argc,argv); QTemporaryDir dir;
    @autoreleasepool {
        const QString value = "vless://test@example.invalid:443#QR-image-test";
        CIImage *first = qr([NSString stringWithUTF8String:value.toUtf8().constData()]);
        QString error; QString path = dir.filePath("qr.png");
        if (!save(first,path) || flintReadQrImageIOS(QUrl::fromLocalFile(path).toString(),error) != value || !error.isEmpty()) return 1;
        CIImage *rotated = [first imageByApplyingTransform:CGAffineTransformMakeRotation(1.5707963267948966)];
        path=dir.filePath("rotated.png"); error.clear();
        if (!save(rotated,path) || flintReadQrImageIOS(QUrl::fromLocalFile(path).toString(),error) != value) return 2;
        CIImage *second = [qr(@"vless://other@example.invalid:443#Second") imageByApplyingTransform:CGAffineTransformMakeTranslation(first.extent.size.width+100,0)];
        path=dir.filePath("multiple.png"); error.clear();
        if (!save([first imageByCompositingOverImage:second],path) || !flintReadQrImageIOS(QUrl::fromLocalFile(path).toString(),error).isEmpty() || error.isEmpty()) return 3;
        path=dir.filePath("blank.png"); error.clear();
        CIImage *blank=[[CIImage imageWithColor:[CIColor colorWithRed:1 green:1 blue:1]] imageByCroppingToRect:CGRectMake(0,0,300,300)];
        if (!save(blank,path) || !flintReadQrImageIOS(QUrl::fromLocalFile(path).toString(),error).isEmpty() || error.isEmpty()) return 4;
        error.clear();
        if (!flintReadQrImageIOS("https://example.invalid/qr.png",error).isEmpty() || error.isEmpty()) return 5;
        qInfo()<<"QR image decoder: valid, rotated, multiple, blank and remote image checks passed";
    }
    return 0;
}
