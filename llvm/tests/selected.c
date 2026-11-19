#include <stdint.h>

static uint32_t method_1(uint32_t left, uint32_t right) {
  return left + right;
}

/* Match generated JNI methods, whose addresses are retained for registration. */
uint32_t (*method_pointer)(uint32_t, uint32_t) = method_1;
