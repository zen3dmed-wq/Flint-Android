# Flint Android / TV 8.10.21

Fix in-app APK installation: the update provider now has a unique Android
component class. In 8.10.19 and 8.10.20, both the Qt files provider and the update
cache provider used androidx.core.content.FileProvider. Android indexes local
providers by package/class, so this could route the update URI to the wrong
provider roots and leave the installer unable to read the downloaded APK.

The client now opens the content URI before launching the system installer and
reports asynchronous launch failures in the update dialog. APK signature,
package, newer version code, hash, Android version and ABI checks remain enforced.

First upgrade from affected 8.10.19/20 must be opened externally (for example,
Samsung My Files), preserving the installed app and its data. The old updater
cannot repair its own code before the new APK is installed.

Website, server settings and published releases are unchanged. The fix must be
verified on a physical Samsung; source/APK checks are not a device test.
