#include <stdint.h>

#define OPERATIONS(APPLY, TYPE, WIDTH)                                         \
  APPLY(add, +, TYPE, WIDTH)                                                   \
  APPLY(sub, -, TYPE, WIDTH)                                                   \
  APPLY(mul, *, TYPE, WIDTH)                                                   \
  APPLY(and, &, TYPE, WIDTH)                                                   \
  APPLY(or, |, TYPE, WIDTH)                                                    \
  APPLY(xor, ^, TYPE, WIDTH)

#define ALL_WIDTHS(APPLY)                                                      \
  OPERATIONS(APPLY, uint8_t, 8)                                                \
  OPERATIONS(APPLY, uint16_t, 16)                                              \
  OPERATIONS(APPLY, uint32_t, 32)                                              \
  OPERATIONS(APPLY, uint64_t, 64)

#ifdef BUILD_FIXTURE

#if defined(_WIN32)
#define FIXTURE_EXPORT __declspec(dllexport)
#else
#define FIXTURE_EXPORT
#endif

#define DEFINE(NAME, OP, TYPE, WIDTH)                                          \
  FIXTURE_EXPORT TYPE method_##NAME##WIDTH(TYPE left, TYPE right) {            \
    return (TYPE)((uint64_t)left OP(uint64_t) right);                          \
  }

ALL_WIDTHS(DEFINE)

#else

#include <inttypes.h>
#include <stdio.h>

#define DECLARE(NAME, OP, TYPE, WIDTH)                                         \
  TYPE method_##NAME##WIDTH(TYPE left, TYPE right);

ALL_WIDTHS(DECLARE)

uint32_t method_chain(uint32_t left, uint32_t right);
uint32_t method_existing_phi(uint32_t left, uint32_t right);

static int compare(uint64_t left, uint64_t right) {
#define CHECK(NAME, OP, TYPE, WIDTH)                                           \
  if (method_##NAME##WIDTH((TYPE)left, (TYPE)right) !=                         \
      (TYPE)(left OP right)) {                                                 \
    fprintf(stderr, #NAME #WIDTH " mismatch for (%" PRIu64 ", %" PRIu64 ")\n", \
            left, right);                                                      \
    return 1;                                                                  \
  }
  ALL_WIDTHS(CHECK)
#undef CHECK

  uint32_t a = (uint32_t)left;
  uint32_t b = (uint32_t)right;
  if (method_chain(a, b) == ((a + b) + 1) * b &&
      method_existing_phi(a, b) == ((a & 1) ? a + b : a - b))
    return 0;
  fprintf(stderr, "chain or PHI mismatch for (%" PRIu64 ", %" PRIu64 ")\n",
          left, right);
  return 1;
}

static uint64_t next_input(uint64_t *state) {
  *state ^= *state << 13;
  *state ^= *state >> 7;
  *state ^= *state << 17;
  return *state;
}

int main(void) {
  for (unsigned a = 0; a <= UINT8_MAX; ++a)
    for (unsigned b = 0; b <= UINT8_MAX; ++b)
      if (compare(a, b))
        return 1;

  const uint64_t edges[] = {0,
                            1,
                            0x7f,
                            0x80,
                            0xff,
                            0x100,
                            0x7fff,
                            0x8000,
                            0xffff,
                            0x10000,
                            0x7fffffff,
                            0x80000000,
                            UINT32_MAX,
                            UINT64_C(0x7fffffffffffffff),
                            UINT64_C(0x8000000000000000),
                            UINT64_MAX - 1,
                            UINT64_MAX};
  for (size_t a = 0; a < sizeof edges / sizeof edges[0]; ++a)
    for (size_t b = 0; b < sizeof edges / sizeof edges[0]; ++b)
      if (compare(edges[a], edges[b]))
        return 1;

  uint64_t state = UINT64_C(0xc0ffee0042f00d55);
  for (unsigned index = 0; index < 100000; ++index) {
    uint64_t left = next_input(&state);
    uint64_t right = next_input(&state);
    if (compare(left, right))
      return 1;
  }
  puts("checked 165825 input pairs across 24 operations, a chain, and a PHI");
  return 0;
}

#endif
