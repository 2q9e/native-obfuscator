#ifndef NATIVE_OBFUSCATOR_NATIVE_TRANSFORMS_H
#define NATIVE_OBFUSCATOR_NATIVE_TRANSFORMS_H

#include "llvm/IR/IRBuilder.h"
#include "llvm/IR/PassManager.h"

#include <cstdint>

namespace nativeobfuscator {

enum class Transform { Encode, Diversify, Mix, Route, Compare, Dispatch };

struct TransformOptions {
  Transform Kind;
  uint64_t Seed = 0;
  unsigned MaxSites;
};

struct RewriteStatistics {
  unsigned CandidateSites = 0;
  unsigned FlagFilteredSites = 0;
  unsigned TypeFilteredSites = 0;
  unsigned UndefinedOperandSites = 0;
  unsigned UnsafeBlockSites = 0;
  unsigned SizeFilteredSites = 0;
  unsigned GuardFilteredSites = 0;
  unsigned EligibleSites = 0;
  unsigned CapSkippedSites = 0;
  unsigned TransformedSites = 0;
  unsigned PrefixFilteredFunctions = 0;
  unsigned OptimizeNoneFunctions = 0;
  unsigned UnsafeControlFlowFunctions = 0;
  unsigned ChangedFunctions = 0;
};

uint64_t nextRandom(uint64_t &State);
uint64_t functionState(const llvm::Function &Function, uint64_t Seed);
bool hasSupportedWidth(llvm::Type *ValueType);
llvm::APInt encodingFactor(unsigned Width, uint64_t &State);
llvm::Value *buildEncoding(llvm::IRBuilder<> &Builder,
                           const llvm::BinaryOperator &Operation,
                           const llvm::APInt &Factor);
llvm::Value *buildOffsetProduct(llvm::IRBuilder<> &Builder, llvm::Value *Left,
                                llvm::Value *Right, const llvm::APInt &Amount,
                                llvm::StringRef Prefix);

bool canDiversify(const llvm::Function &Function, bool IsRoute);
llvm::PHINode *branchOperation(llvm::BinaryOperator &Operation,
                               llvm::Value &Guard, uint64_t &State,
                               bool IsRoute);

bool rewriteIntegerOperations(llvm::Function &Function,
                              const TransformOptions &Config,
                              RewriteStatistics &Statistics);
bool rewriteComparisons(llvm::Function &Function,
                        const TransformOptions &Config,
                        RewriteStatistics &Statistics);
bool rewriteDispatch(llvm::Function &Function,
                     llvm::FunctionAnalysisManager &Analysis,
                     const TransformOptions &Config,
                     RewriteStatistics &Statistics);

} // namespace nativeobfuscator

#endif // NATIVE_OBFUSCATOR_NATIVE_TRANSFORMS_H
