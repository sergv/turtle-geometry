Overview
========
This is Turtle Geometry android application initially developed to solve exercises
for the amazing [Turtle Geometry book](http://mitpress.mit.edu/books/turtle-geometry).
The Turtle Procedure Notation from book is replaced by mostly R4RS-compatible Scheme dialect.
Specifically, the dialect is whatever the patched [JScheme](https://github.com/sergv/jscheme) implements.

And this is how it looks like while drawing general spirolateral shape (feel free
to download if your browser doesn't show animation here):

![General spirolateral](https://raw.github.com/sergv/turtle-geometry/master/general_spirolateral.gif)

Build process
=============

Prerequisites
-------------
Application is written in Clojure and is built using Leiningen lein-droid plugin.
Quick installation instructions for Leiningen can be found [here](http://leiningen.org).
Details about lein-droid plugin are covered [here](https://github.com/clojure-android/lein-droid).

Since this is an android application, full Android SDK will be required for build. Consult
[Google guidelines](http://developer.android.com/sdk/index.html) on how to install it.
By default the application is configured for Android 2.3 (API 10) so either this
platform should be installed through sdk manager or project.clj must be adjusted
to different version. As a side note, android build tools (listed among available
packages in sdk manager) must be installed as well.

After Android SDK and Leiningen are installed the next step is to build and install
[android-utils](https://github.com/sergv/android-utils) support library.

Android device prerequisites
----------------------------
Currently for loading and saving files
[AndExplorer](https://play.google.com/store/apps/details?id=lysesoft.andexplorer)
application, used for querying file names, is required.

Bulding Turtle Geometry
-----------------------
Now its time to fill some user-specific variables in `project-key.clj` file near
`project.clj`. Sample syntax for `project.clj` may be found
[here](https://github.com/technomancy/leiningen/blob/master/sample.project.clj).
Entries of `project-key.clj` should follow syntax of entries under `:android` key
as described in [sample project for lein-droid](https://github.com/clojure-android/lein-droid/blob/master/sample/project.clj).
Create empty `project-key.clj` and fill it according to following template

```clojure
{:sdk-path ... ;; path to location where android sdk is installed, hopefully should be the same as SDK_HOME environment variable

 ;; specify keystore if you have one
 :keystore-path ...
 :key-alias ...
 :storepass ...
 :keypass ...}
```

When `lein` executable is accessible through `PATH` and `project-key.clj` is filled,
plug in your device and issue

```shell
lein with-profiles release do droid code-gen, droid compile, droid create-dex, droid apk, droid install, droid run
```

Or, if you don't have dedicated keystore then try above command with `debug` instead of `release`:

```shell
lein with-profiles debug do droid code-gen, droid compile, droid create-dex, droid apk, droid install, droid run
```

Disclaimer
==========
I'm not in any way associated with production or copyright holders of
Turtle Geometry book and do not claim ownership of the logo icon.

