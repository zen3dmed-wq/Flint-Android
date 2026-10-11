from pathlib import Path

def apply(root: Path):
    header=root/'client/ui/controllers/importUiController.h'
    text=header.read_text(encoding='utf-8')
    anchor='    void qrDecodingFinished();'
    assert anchor in text
    text=text.replace(anchor,anchor+'\n    void flintSubscriptionQr(const QString &url);')
    header.write_text(text,encoding='utf-8')
    cpp=root/'client/ui/controllers/importUiController.cpp'
    text=cpp.read_text(encoding='utf-8')
    anchor='bool ImportUiController::parseQrCodeChunk(const QString &code)\n{'
    assert anchor in text
    text=text.replace(anchor,anchor+'''
    if(code.trimmed().startsWith("https://",Qt::CaseInsensitive) || code.trimmed().startsWith("flint://pair") || code.trimmed().startsWith("flint://connect/")) {
        emit flintSubscriptionQr(code.trimmed());
        stopDecodingQr();
        return true;
    }
''')
    cpp.write_text(text,encoding='utf-8')
