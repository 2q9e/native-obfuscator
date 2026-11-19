#include <inttypes.h>
#include <stdint.h>
#include <stdio.h>

uint32_t method_sum32(uint32_t left, uint32_t right);
uint64_t method_sub64(uint64_t left, uint64_t right);
uint32_t method_sum8(uint32_t left, uint32_t right);
uint32_t method_sub16(uint32_t left, uint32_t right);
uint32_t method_chain32(uint32_t left, uint32_t right);
uint32_t method_phi32(uint32_t left, uint32_t right);

static uint64_t next_input(uint64_t *state) {
  *state ^= *state << 13;
  *state ^= *state >> 7;
  *state ^= *state << 17;
  return *state;
}

static int compare(uint64_t left, uint64_t right) {
  uint32_t a = (uint32_t)left;
  uint32_t b = (uint32_t)right;
  if (method_sum32(a, b) == a + b && method_sub64(left, right) == left - right &&
      method_sum8(a, b) == ((a + b) & UINT32_C(0xff)) &&
      method_sub16(a, b) == ((a - b) & UINT32_C(0xffff)) &&
      method_chain32(a, b) == (((a + b) ^ b) - a) &&
      method_phi32(a, b) == ((a & 1) ? a + b : a - b))
    return 0;
  fprintf(stderr, "arithmetic mismatch for (%" PRIu64 ", %" PRIu64 ")\n",
          left, right);
  return 1;
}

int main(void) {
  const uint64_t boundaries[] = {
      0, 1, 2, 0x7f, 0x80, 0xff, 0x100, 0x7fff, 0x8000, 0xffff, 0x10000,
      UINT64_C(0x7fffffff), UINT64_C(0x80000000), UINT32_MAX,
      UINT64_C(0xfffffffffffffffe), UINT64_MAX,
      UINT64_C(0x7fffffffffffffff), UINT64_C(0x8000000000000000)};
  for (size_t i = 0; i < sizeof boundaries / sizeof boundaries[0]; ++i)
    for (size_t j = 0; j < sizeof boundaries / sizeof boundaries[0]; ++j)
      if (compare(boundaries[i], boundaries[j]))
        return 1;

  uint64_t state = UINT64_C(0x5ba870ae33c2d306);
  for (unsigned i = 0; i < 100000; ++i) {
    uint64_t left = next_input(&state);
    uint64_t right = next_input(&state);
    if (compare(left, right))
      return 1;
  }
  puts("checked 100324 input pairs");
  return 0;
}
