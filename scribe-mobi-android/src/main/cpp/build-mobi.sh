#!/bin/sh
set -eu
mobi_src=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
mobi_output=$1
mobi_java=$2
mkdir -p "$mobi_output/arm64-v8a"
clang -std=c99 -O2 -fPIC -fvisibility=hidden -shared \
    -DPACKAGE_VERSION='"0.12"' -DHAVE_STRDUP -DHAVE_UNISTD_H -DMOBI_INLINE=inline \
    -I"$mobi_java/include" -I"$mobi_java/include/linux" -I"$mobi_src/third_party/libmobi" \
    "$mobi_src/mobi_jni.c" \
    "$mobi_src/third_party/libmobi/buffer.c" "$mobi_src/third_party/libmobi/compression.c" \
    "$mobi_src/third_party/libmobi/debug.c" "$mobi_src/third_party/libmobi/index.c" \
    "$mobi_src/third_party/libmobi/memory.c" "$mobi_src/third_party/libmobi/meta.c" \
    "$mobi_src/third_party/libmobi/parse_rawml.c" "$mobi_src/third_party/libmobi/read.c" \
    "$mobi_src/third_party/libmobi/structure.c" "$mobi_src/third_party/libmobi/util.c" \
    "$mobi_src/third_party/libmobi/write.c" -lz \
    -Wl,-z,max-page-size=16384 -Wl,-soname,libinfinimobi.so -o "$mobi_output/arm64-v8a/libinfinimobi.so"

# Match Android's public zlib soname; never retain Termux-only library paths.
patchelf --replace-needed libz.so.1 libz.so "$mobi_output/arm64-v8a/libinfinimobi.so"
patchelf --remove-rpath "$mobi_output/arm64-v8a/libinfinimobi.so"
