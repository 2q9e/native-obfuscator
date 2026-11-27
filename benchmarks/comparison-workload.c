#if !defined(_WIN32)
#define _POSIX_C_SOURCE 200809L
#endif

#include <inttypes.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#ifdef _WIN32
#include <windows.h>
#endif

#if defined(_MSC_VER)
#define NOINLINE __declspec(noinline)
#elif defined(__GNUC__) || defined(__clang__)
#define NOINLINE __attribute__((noinline))
#else
#define NOINLINE
#endif
#ifndef BENCHMARK_KERNEL
#define BENCHMARK_KERNEL
#endif

enum { BATCH = 32 };

BENCHMARK_KERNEL NOINLINE uint32_t workload_step(uint32_t value) {
    return value ^ UINT32_C(0x13271953);
}

static uint32_t recurrence(uint32_t value, uint32_t index) {
    return ((value ^ UINT32_C(0xa37d39b1)) * UINT32_C(0x72b3f89d))
           + (index ^ UINT32_C(0x72a57b21));
}

BENCHMARK_KERNEL NOINLINE uint32_t workload_mix(uint32_t value, uint32_t index) {
    return recurrence(value, index);
}

BENCHMARK_KERNEL NOINLINE uint32_t workload_batch(uint32_t value, uint32_t start, uint32_t count) {
    for (uint32_t offset = 0; offset < count; ++offset) {
        value = recurrence(value, start + offset);
    }
    return value;
}

static uint64_t now_ns(void) {
#ifdef _WIN32
    LARGE_INTEGER ticks, frequency;
    if (!QueryPerformanceCounter(&ticks) || !QueryPerformanceFrequency(&frequency)) {
        exit(EXIT_FAILURE);
    }
    return (uint64_t)((double)ticks.QuadPart * 1e9 / (double)frequency.QuadPart);
#else
    struct timespec time;
    if (clock_gettime(CLOCK_MONOTONIC, &time) != 0) {
        exit(EXIT_FAILURE);
    }
    return (uint64_t)time.tv_sec * UINT64_C(1000000000) + (uint64_t)time.tv_nsec;
#endif
}

static uint32_t step_trial(uint32_t value, uint32_t iterations) {
    for (uint32_t index = 0; index < iterations; ++index) {
        value = workload_step(value + index);
    }
    return value;
}

static uint32_t mix_trial(uint32_t value, uint32_t iterations) {
    for (uint32_t index = 0; index < iterations; ++index) {
        value = workload_mix(value, index);
    }
    return value;
}

static uint32_t batch_trial(uint32_t value, uint32_t iterations) {
    for (uint32_t index = 0; index < iterations; index += BATCH) {
        value = workload_batch(value, index, BATCH);
    }
    return value;
}

static uint32_t probe(uint32_t value) {
    uint32_t result = value;
    for (uint32_t index = 0; index < 4096; ++index) {
        value = value * UINT32_C(1664525) + UINT32_C(1013904223);
        uint32_t next = workload_mix(value, index);
        if (next != workload_batch(value, index, 1)) {
            exit(EXIT_FAILURE);
        }
        result = (result << 5 | result >> 27) ^ workload_step(next);
    }
    return result;
}

static void sample(const char *name, uint64_t elapsed, uint32_t result) {
    printf("%s %" PRIu64 " %08" PRIx32 "\n", name, elapsed, result);
}

typedef uint32_t (*Trial)(uint32_t, uint32_t);

/* A volatile function pointer prevents repeated identical trials from being
   folded into one call or moved outside the timed region. */
static void measure(const char *name, Trial trial, uint32_t seed, uint32_t iterations,
                    uint32_t warmups, uint32_t trials) {
    Trial volatile run = trial;
    uint32_t expected = run(seed, iterations);
    for (uint32_t i = 1; i < warmups; ++i) {
        if (run(seed, iterations) != expected) {
            exit(EXIT_FAILURE);
        }
    }
    for (uint32_t i = 0; i < trials; ++i) {
        uint64_t start = now_ns();
        uint32_t result = run(seed, iterations);
        uint64_t elapsed = now_ns() - start;
        if (result != expected) {
            exit(EXIT_FAILURE);
        }
        sample(name, elapsed, result);
    }
}

int main(int argc, char **argv) {
    if (argc < 3) {
        return EXIT_FAILURE;
    }
    uint32_t seed = (uint32_t)strtoul(argv[2], NULL, 10);
    if (argc == 3 && strcmp(argv[1], "probe") == 0) {
        sample("probe", 0, probe(seed));
        return EXIT_SUCCESS;
    }
    if (argc == 3 && strcmp(argv[1], "startup") == 0) {
        uint32_t (*volatile run)(uint32_t) = workload_step;
        uint64_t start = now_ns();
        uint32_t result = run(seed);
        sample("startup", now_ns() - start, result);
        return EXIT_SUCCESS;
    }
    if (argc != 6 || strcmp(argv[1], "steady") != 0) {
        return EXIT_FAILURE;
    }
    uint32_t iterations = (uint32_t)strtoul(argv[3], NULL, 10);
    uint32_t warmups = (uint32_t)strtoul(argv[4], NULL, 10);
    uint32_t trials = (uint32_t)strtoul(argv[5], NULL, 10);
    if (iterations == 0 || iterations % BATCH || warmups == 0 || trials == 0) {
        return EXIT_FAILURE;
    }
    measure("step", step_trial, seed, iterations, warmups, trials);
    measure("per_call", mix_trial, seed, iterations, warmups, trials);
    measure("batched_32", batch_trial, seed, iterations, warmups, trials);
    return EXIT_SUCCESS;
}
