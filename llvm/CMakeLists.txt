cmake_minimum_required(VERSION 3.20)
project(NativeEncode LANGUAGES C CXX)

find_package(LLVM REQUIRED CONFIG)
if(NOT LLVM_VERSION_MAJOR MATCHES "^(22|23)$")
  message(FATAL_ERROR "NativeEncode requires LLVM 22 or 23, found ${LLVM_PACKAGE_VERSION}")
endif()
list(APPEND CMAKE_MODULE_PATH "${LLVM_CMAKE_DIR}")
include(AddLLVM)

add_llvm_pass_plugin(NativeEncode
  NativeEncode.cpp
  IntegerTransforms.cpp
  ControlFlowTransforms.cpp)
target_include_directories(NativeEncode SYSTEM PRIVATE ${LLVM_INCLUDE_DIRS})
separate_arguments(llvm_definitions NATIVE_COMMAND "${LLVM_DEFINITIONS}")
target_compile_options(NativeEncode PRIVATE ${llvm_definitions})
target_compile_features(NativeEncode PRIVATE cxx_std_17)

include(CTest)
if(BUILD_TESTING AND CMAKE_SYSTEM_NAME STREQUAL "Linux")
  add_test(NAME llvm-function-reports
    COMMAND bash "${CMAKE_CURRENT_SOURCE_DIR}/tests/verify-reports.sh"
      "$<TARGET_FILE:NativeEncode>" "${LLVM_TOOLS_BINARY_DIR}")
  foreach(pass IN ITEMS native-encode native-diversify)
    add_test(
      NAME "llvm-${pass}"
      COMMAND bash "${CMAKE_CURRENT_SOURCE_DIR}/tests/verify.sh"
        "$<TARGET_FILE:NativeEncode>" "${LLVM_TOOLS_BINARY_DIR}"
        "${CMAKE_C_COMPILER}" "${pass}")
  endforeach()
  find_package(Python3 QUIET COMPONENTS Interpreter)
  if(Python3_Interpreter_FOUND)
    add_test(NAME llvm-native-extended
      COMMAND "${Python3_EXECUTABLE}"
        "${CMAKE_CURRENT_SOURCE_DIR}/tests/verify-extended.py"
        --llvm-bin "${LLVM_TOOLS_BINARY_DIR}" --plugin "$<TARGET_FILE:NativeEncode>"
        --cc "${CMAKE_C_COMPILER}")
    add_test(NAME llvm-native-randomized
      COMMAND "${Python3_EXECUTABLE}"
        "${CMAKE_CURRENT_SOURCE_DIR}/tests/verify-randomized.py"
        --llvm-bin "${LLVM_TOOLS_BINARY_DIR}" --plugin "$<TARGET_FILE:NativeEncode>"
        --cc "${CMAKE_C_COMPILER}")
  endif()
endif()
