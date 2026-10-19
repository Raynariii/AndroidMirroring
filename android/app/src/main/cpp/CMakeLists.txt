cmake_minimum_required(VERSION 3.22.1)
project(androidmirroring_native C)

# BoringSSL comes from the io.github.vvb2060.ndk:boringssl prefab package.
find_package(boringssl REQUIRED CONFIG)

add_library(androidmirroring_native SHARED spake2_jni.c)
target_link_libraries(androidmirroring_native boringssl::crypto_static)
