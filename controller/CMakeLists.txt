cmake_minimum_required(VERSION 3.15)
project(boiler_controller CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Cœur de régulation portable — aucune dépendance, aucune E/S matérielle.
add_library(boiler_controller STATIC
    src/boiler_controller.cpp)
target_include_directories(boiler_controller PUBLIC include)
target_compile_options(boiler_controller PRIVATE -Wall -Wextra -Wpedantic)

enable_testing()
add_subdirectory(tests)
