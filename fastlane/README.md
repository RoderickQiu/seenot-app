fastlane documentation
----

# Installation

Make sure you have the latest version of the Xcode command line tools installed:

```sh
xcode-select --install
```

For _fastlane_ installation instructions, see [Installing _fastlane_](https://docs.fastlane.tools/#installing-fastlane)

# Available Actions

## Android

### android build_debug

```sh
[bundle exec] fastlane android build_debug
```

Build debug APK

### android release

```sh
[bundle exec] fastlane android release
```

Build signed release artifacts (APK + AAB)

### android ci_release

```sh
[bundle exec] fastlane android ci_release
```

CI lane for signed release build

### android metadata_check

```sh
[bundle exec] fastlane android metadata_check
```

Validate minimum fastlane metadata expected by IzzyOnDroid/F-Droid

----

This README.md is auto-generated and will be re-generated every time [_fastlane_](https://fastlane.tools) is run.

More information about _fastlane_ can be found on [fastlane.tools](https://fastlane.tools).

The documentation of _fastlane_ can be found on [docs.fastlane.tools](https://docs.fastlane.tools).
