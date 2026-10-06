#!/bin/bash
set -euo pipefail
source_dir="${1:?Path to patched amnezia-client source}"
build_dir="${2:?Build output path}"
: "${QT_ROOT_PATH:?Set QT_ROOT_PATH to the Qt version directory containing macos and ios}"
cmake -S "$source_dir" -B "$build_dir" -G Xcode \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_CONFIGURATION_TYPES=Release \
  -DCMAKE_SYSTEM_NAME=iOS -DCMAKE_OSX_SYSROOT=iphoneos \
  -DCMAKE_TOOLCHAIN_FILE="$QT_ROOT_PATH/ios/lib/cmake/Qt6/qt.toolchain.cmake" \
  -DQT_HOST_PATH="$QT_ROOT_PATH/macos" \
  -DCMAKE_XCODE_ATTRIBUTE_CODE_SIGNING_ALLOWED=NO \
  -DCMAKE_XCODE_ATTRIBUTE_CODE_SIGNING_REQUIRED=NO \
  -DAMNEZIA_BUILD_TESTS=OFF
cmake --build "$build_dir" --config Release --parallel 3 -- \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY=
