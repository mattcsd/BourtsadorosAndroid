cmake_minimum_required(VERSION 3.22.1)
project("bourtsadoros")

add_library(soundtouch STATIC
        soundtouch/AAFilter.cpp
        soundtouch/FIFOSampleBuffer.cpp
        soundtouch/RateTransposer.cpp
        soundtouch/SoundTouch.cpp
        soundtouch/TDStretch.cpp
        soundtouch/FIRFilter.cpp
        soundtouch/InterpolateLinear.cpp
        soundtouch/InterpolateShannon.cpp
        soundtouch/InterpolateCubic.cpp
        soundtouch/PeakFinder.cpp
        soundtouch/BPMDetect.cpp
        soundtouch/cpu_detect_x86.cpp
        soundtouch/cpu_detect_arm.cpp
)

target_include_directories(soundtouch PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/soundtouch)

add_library(bourtsadoros_jni SHARED
        SoundTouchJNI.cpp
)

target_include_directories(bourtsadoros_jni PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(bourtsadoros_jni soundtouch log)