"""Add native, on-device decoding for a user-selected QR image."""
from pathlib import Path
import shutil

def apply(root):
    here=Path(__file__).resolve().parent/'flint'
    for name in ['flintQrImage.cpp','flintQrImageIOS.mm']:
        shutil.copy2(here/name, root/'client/ui/controllers'/name)
    shutil.copy2(here/'FlintQrImage.kt', root/'client/android/src/org/amnezia/vpn/FlintQrImage.kt')
    path=root/'client/CMakeLists.txt'
    text=path.read_text(encoding='utf-8')
    marker='target_sources(${PROJECT} PRIVATE ${SOURCES} ${HEADERS} ${RESOURCES} ${QRC} ${I18NQRC})'
    assert marker in text
    # Controller .cpp files are already collected by the upstream source glob.
    text=text.replace(marker,marker+'''
if(IOS)
    target_sources(${PROJECT} PRIVATE ui/controllers/flintQrImageIOS.mm)
    target_link_libraries(${PROJECT} PRIVATE "-framework CoreImage" "-framework ImageIO")
endif()
''')
    path.write_text(text,encoding='utf-8')
