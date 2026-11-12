# VideoLAN's media library, built with our fixes

The app takes VideoLAN's media library as a package (`org.videolan.android:medialibrary-all`). When a bug is in the
media library itself, the package is built here from VideoLAN's tagged source with our patches, and published as
`<version>-rdN` (for example `0.13.24-rd1`) to a local Maven repository the build reads first (`build.gradle`,
`RD_MAVEN`, by default `~/source/vlc-redesign-android-maven`). Only the native library `libmla.so` differs from
VideoLAN's package; its Java classes and `libc++_shared.so` are VideoLAN's.

## The fixes

`buildsystem/patches/medialibrary/` (applied by `compile-medialibrary.sh` after the source is fetched):

- `0001`: a song's picture stored as a video track (a still-image codec in a file named as audio, next to an audio
  track) is the song's cover, not a video ([#81](https://github.com/KuchiSofts/vlc-redesign/issues/81)). The desktop
  has the same rule in `modules/misc/medialibrary/cover_track.h`.

## Building it

VideoLAN's own build images; the 32-bit types need NDK 21, the 64-bit ones NDK 27 to 29:

| Types | Image |
|---|---|
| `arm`, `x86` | `registry.videolan.org/vlc-debian-android-3.0:20260701183656` |
| `arm64`, `x86_64` | `registry.videolan.org/vlc-debian-android:20260611225331` |

For each type, libvlc first, then the media library (`-ml` alone skips libvlc once another type has it):

```
buildsystem/compile.sh -a <type> -t -l --release
buildsystem/compile.sh -a <type> -t -ml --release
```

The four types take about 50 minutes. Then, for each type, `llvm-strip --strip-unneeded` on
`medialibrary/jni/libs/<abi>/libmla.so`, and VideoLAN's `medialibrary-all-<version>.aar` with its four `libmla.so`
replaced, with its `.pom` (version `<version>-rdN`), goes to
`<RD_MAVEN>/org/videolan/android/medialibrary-all/<version>-rdN/`.

## When VideoLAN updates the media library

Move `MEDIALIBRARY_HASH` and `medialibraryVersion` to theirs, check that the patches still apply, build again and
publish `-rd1` of the new version. When VideoLAN fixes a bug themselves, its patch is dropped; with no patch left,
the app goes back to VideoLAN's package.
