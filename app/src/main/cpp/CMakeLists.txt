cmake_minimum_required(VERSION 3.22)
project(needle_jni CXX)

add_library(cactus STATIC IMPORTED)
set_target_properties(cactus PROPERTIES IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/libs/libcactus.a)

add_library(needle_jni SHARED needle_jni.cpp)
target_link_libraries(needle_jni cactus log android)
