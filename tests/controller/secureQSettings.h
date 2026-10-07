#pragma once
#include <QSettings>
// The tests exercise routing and session invalidation, not the upstream
// platform encryption implementation.
using SecureQSettings = QSettings;
