cmake_minimum_required(VERSION 3.22.1)
project(node_bridge)

set(CMAKE_CXX_STANDARD 17)

# libnode.so is placed into jniLibs/<abi>/ by the CI workflow before the build
# (same flow as libcloudflared.so). For local builds without it, skip node_bridge
# gracefully — the Kotlin side detects the missing library and disables the
# embedded backend instead of crashing.
set(LIBNODE_PATH ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libnode.so)
if(EXISTS ${LIBNODE_PATH})
    add_library(libnode SHARED IMPORTED)
    set_target_properties(libnode PROPERTIES IMPORTED_LOCATION ${LIBNODE_PATH})

    add_library(node_bridge SHARED node_bridge.cpp)

    find_library(log-lib log)
    target_link_libraries(node_bridge libnode ${log-lib})
else()
    message(WARNING "libnode.so not found at ${LIBNODE_PATH} — node_bridge not built; embedded AyX backend will be unavailable in this APK")
endif()
