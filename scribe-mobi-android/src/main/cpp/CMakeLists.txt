cmake_minimum_required(VERSION 3.22.1)
project(infinimobi C)
set(mobi_src third_party/libmobi)
add_library(infinimobi SHARED mobi_jni.c
    ${mobi_src}/buffer.c ${mobi_src}/compression.c ${mobi_src}/debug.c
    ${mobi_src}/index.c ${mobi_src}/memory.c ${mobi_src}/meta.c ${mobi_src}/parse_rawml.c
    ${mobi_src}/read.c ${mobi_src}/structure.c ${mobi_src}/util.c ${mobi_src}/write.c)
target_include_directories(infinimobi PRIVATE ${mobi_src})
target_compile_definitions(infinimobi PRIVATE PACKAGE_VERSION="0.12" HAVE_STRDUP HAVE_UNISTD_H MOBI_INLINE=inline)
target_link_libraries(infinimobi z)
target_link_options(infinimobi PRIVATE "-Wl,-z,max-page-size=16384")
