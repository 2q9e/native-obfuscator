#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

#define FOR_EACH_PREDICATE(M, W, S, U) \
    M(slt, W, S, <) M(sle, W, S, <=) M(sgt, W, S, >) M(sge, W, S, >=) \
    M(ult, W, U, <) M(ule, W, U, <=) M(ugt, W, U, >) M(uge, W, U, >=)

#define DECLARE(P, W, T, OP) extern int method_##P##W(uint64_t, uint64_t);
FOR_EACH_PREDICATE(DECLARE, 8, int8_t, uint8_t)
FOR_EACH_PREDICATE(DECLARE, 16, int16_t, uint16_t)
FOR_EACH_PREDICATE(DECLARE, 32, int32_t, uint32_t)
FOR_EACH_PREDICATE(DECLARE, 64, int64_t, uint64_t)
extern uint32_t method_compare_loop(uint32_t);

int compare_true(void) { return 37; }
int compare_false(void) { return -19; }

static unsigned checks;

#define CHECK(P, W, T, OP)                                                  \
    do {                                                                   \
        if (method_##P##W(left, right) != ((T)left OP (T)right ? 37 : -19)) { \
            fprintf(stderr, #P #W " mismatch\n");                         \
            exit(1);                                                       \
        }                                                                  \
        checks++;                                                          \
    } while (0);

static void check8(uint64_t left, uint64_t right) {
    FOR_EACH_PREDICATE(CHECK, 8, int8_t, uint8_t)
}

static void checkWide(uint64_t left, uint64_t right) {
    FOR_EACH_PREDICATE(CHECK, 16, int16_t, uint16_t)
    FOR_EACH_PREDICATE(CHECK, 32, int32_t, uint32_t)
    FOR_EACH_PREDICATE(CHECK, 64, int64_t, uint64_t)
}

static uint64_t next(uint64_t *state) {
    *state = *state * UINT64_C(6364136223846793005) + 1;
    return *state;
}

int main(void) {
    for (unsigned left = 0; left < 256; left++)
        for (unsigned right = 0; right < 256; right++)
            check8(left, right);

    const uint64_t edge[] = {
        0, 1, 0x7fff, 0x8000, 0x8001, 0xffff, 0x10000,
        0x7fffffff, 0x80000000, 0x80000001, 0xffffffff, UINT64_C(0x100000000),
        UINT64_C(0x7fffffffffffffff), UINT64_C(0x8000000000000000),
        UINT64_C(0x8000000000000001), UINT64_MAX,
    };
    for (unsigned a = 0; a < sizeof edge / sizeof *edge; a++)
        for (unsigned b = 0; b < sizeof edge / sizeof *edge; b++)
            checkWide(edge[a], edge[b]);

    uint64_t state = UINT64_C(0x5314d3be7a976402);
    for (unsigned i = 0; i < 4096; i++) {
        uint64_t left = next(&state);
        uint64_t right = next(&state);
        checkWide(left, right);
        checkWide(left, left ^ 31);
    }
    for (uint32_t n = 0; n < 128; n++) {
        if (method_compare_loop(n) != (n ? n : 1))
            return 1;
    }
    printf("checked %u integer relations and 128 loop lengths\n", checks);
    return 0;
}
