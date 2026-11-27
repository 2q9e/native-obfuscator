"""Apply only O-MVLL's arithmetic pass to the three comparison kernels."""

from functools import lru_cache

import omvll


KERNELS = {"workload_step", "workload_mix", "workload_batch"}


class BenchmarkConfig(omvll.ObfuscationConfig):
    omvll.config.probability_seed = 42
    omvll.config.shuffle_functions = False
    omvll.config.inline_jni_wrappers = False

    def obfuscate_arithmetic(self, module, function):
        return omvll.ArithmeticOpt(rounds=2) if function.name in KERNELS else False


@lru_cache(maxsize=1)
def omvll_get_config():
    return BenchmarkConfig()
