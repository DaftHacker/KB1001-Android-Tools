cmake_minimum_required(VERSION 3.22.1)
project(kb1001_fps_validator)

add_library(fpsvalidator SHARED fps_validator.cpp)

find_library(log-lib log)
find_library(android-lib android)
find_library(egl-lib EGL)
find_library(gles-lib GLESv2)

target_link_libraries(
        fpsvalidator
        ${log-lib}
        ${android-lib}
        ${egl-lib}
        ${gles-lib})
