#include "llvm/ADT/APInt.h"
#include "llvm/ADT/SmallVector.h"
#include "llvm/ADT/StringRef.h"
#include "llvm/Config/llvm-config.h"
#include "llvm/IR/IRBuilder.h"
#include "llvm/IR/InstIterator.h"
#include "llvm/IR/PassManager.h"
#include "llvm/Passes/PassBuilder.h"
#include "llvm/Plugins/PassPlugin.h"
#include "llvm/Support/raw_ostream.h"
#include <cstdint>
#include <string>
#include <utility>

namespace {

struct Options {
  uint64_t Seed = 0;
  unsigned MaxSites = 8;
  std::string Prefix;
};

bool isPrefixCharacter(char C) {
  return (C >= 'a' && C <= 'z') || (C >= 'A' && C <= 'Z') ||
         (C >= '0' && C <= '9') || C == '_' || C == '.' || C == '$';
}

bool readOptions(llvm::StringRef Name, Options &Config) {
  if (Name == "native-encode")
    return true;
  if (!Name.consume_front("native-encode<") || !Name.consume_back(">") ||
      Name.empty() || Name.ends_with(";"))
    return false;

  bool HasSeed = false;
  bool HasMax = false;
  bool HasPrefix = false;
  while (!Name.empty()) {
    auto [Option, Remaining] = Name.split(';');
    auto [Key, Value] = Option.split('=');
    if (Key == "seed" && !HasSeed && !Value.getAsInteger(10, Config.Seed)) {
      HasSeed = true;
    } else if (Key == "max" && !HasMax &&
               !Value.getAsInteger(10, Config.MaxSites)) {
      HasMax = true;
    } else if (Key == "prefix" && !HasPrefix && !Value.empty()) {
      for (char C : Value) {
        if (!isPrefixCharacter(C))
          return false;
      }
      Config.Prefix = Value.str();
      HasPrefix = true;
    } else {
      return false;
    }
    Name = Remaining;
  }
  return true;
}

uint64_t nextFactor(uint64_t &State) {
  State += UINT64_C(0x9e3779b97f4a7c15);
  uint64_t Mixed = State;
  Mixed = (Mixed ^ (Mixed >> 30)) * UINT64_C(0xbf58476d1ce4e5b9);
  Mixed = (Mixed ^ (Mixed >> 27)) * UINT64_C(0x94d049bb133111eb);
  return (Mixed ^ (Mixed >> 31)) | UINT64_C(1);
}

llvm::APInt modularInverse(const llvm::APInt &Factor) {
  unsigned Width = Factor.getBitWidth();
  llvm::APInt Inverse(Width, 1);
  llvm::APInt Two(Width, 2);
  // An odd factor is its own inverse modulo two. Each Newton step
  // doubles the number of correct low bits.
  for (unsigned Bits = 1; Bits < Width; Bits *= 2)
    Inverse *= Two - Factor * Inverse;
  return Inverse;
}

bool canEncode(const llvm::BinaryOperator &Operation) {
  if (Operation.getOpcode() != llvm::Instruction::Add &&
      Operation.getOpcode() != llvm::Instruction::Sub)
    return false;
  auto *Type = llvm::dyn_cast<llvm::IntegerType>(Operation.getType());
  if (!Type || Operation.hasNoUnsignedWrap() || Operation.hasNoSignedWrap())
    return false;
  switch (Type->getBitWidth()) {
  case 8:
  case 16:
  case 32:
  case 64:
    return true;
  default:
    return false;
  }
}

bool encodeFunction(llvm::Function &Function, const Options &Config) {
  if (Config.MaxSites == 0 || !Function.getName().starts_with(Config.Prefix) ||
      Function.hasFnAttribute(llvm::Attribute::OptimizeNone))
    return false;

  llvm::SmallVector<llvm::BinaryOperator *, 8> Sites;
  for (llvm::Instruction &Instruction : llvm::instructions(Function)) {
    auto *Operation = llvm::dyn_cast<llvm::BinaryOperator>(&Instruction);
    if (Operation && canEncode(*Operation)) {
      Sites.push_back(Operation);
      if (Sites.size() == Config.MaxSites)
        break;
    }
  }

  uint64_t State = UINT64_C(0xcbf29ce484222325);
  for (unsigned char C : Function.getName()) {
    State ^= C;
    State *= UINT64_C(0x100000001b3);
  }
  State ^= Config.Seed;

  for (llvm::BinaryOperator *Operation : Sites) {
    auto *Type = llvm::cast<llvm::IntegerType>(Operation->getType());
    unsigned Width = Type->getBitWidth();
    llvm::APInt Factor = llvm::APInt(64, nextFactor(State)).zextOrTrunc(Width);
    if (Factor.isOne() || Factor.isAllOnes())
      Factor = llvm::APInt(Width, 3);

    llvm::IRBuilder<> Builder(Operation);
    Builder.SetCurrentDebugLocation(Operation->getDebugLoc());
    auto *Scale = llvm::ConstantInt::get(Type, Factor);
    auto *Inverse = llvm::ConstantInt::get(Type, modularInverse(Factor));
    // Work in Z/(2^N). The encoding is linear, and the odd scale is
    // invertible. Each original operand is used once.
    auto *Left = Builder.CreateMul(Operation->getOperand(0), Scale,
                                   "native.encode.left");
    auto *Right = Builder.CreateMul(Operation->getOperand(1), Scale,
                                    "native.encode.right");
    auto *Encoded =
        Builder.CreateBinOp(Operation->getOpcode(), Left, Right,
                            "native.encode.combined");
    auto *Result = Builder.CreateMul(Encoded, Inverse, "native.encode.result");
    if (auto *ResultInstruction = llvm::dyn_cast<llvm::Instruction>(Result))
      ResultInstruction->takeName(Operation);
    Operation->replaceAllUsesWith(Result);
    Operation->eraseFromParent();
  }
  return !Sites.empty();
}

class NativeEncode : public llvm::OptionalPassInfoMixin<NativeEncode> {
  Options Config;

public:
  explicit NativeEncode(Options Config) : Config(std::move(Config)) {}

  llvm::PreservedAnalyses run(llvm::Module &Module,
                              llvm::ModuleAnalysisManager &) {
    bool Changed = false;
    for (llvm::Function &Function : Module)
      Changed |= encodeFunction(Function, Config);
    return Changed ? llvm::PreservedAnalyses::none()
                   : llvm::PreservedAnalyses::all();
  }
};

void registerPass(llvm::PassBuilder &Builder) {
  Builder.registerPipelineParsingCallback(
      [](llvm::StringRef Name, llvm::ModulePassManager &Manager,
         llvm::ArrayRef<llvm::PassBuilder::PipelineElement> InnerPipeline) {
        if (Name != "native-encode" && !Name.starts_with("native-encode<"))
          return false;
        Options Config;
        if (!InnerPipeline.empty() || !readOptions(Name, Config)) {
          llvm::errs() << "invalid native-encode options; expected "
                          "native-encode<seed=UINT64;max=UINT;prefix=NAME>\n";
          return false;
        }
        Manager.addPass(NativeEncode(std::move(Config)));
        return true;
      });
}

} // namespace

extern "C" LLVM_ATTRIBUTE_WEAK llvm::PassPluginLibraryInfo
llvmGetPassPluginInfo() {
  return {LLVM_PLUGIN_API_VERSION, "NativeEncode", "0.1.0", registerPass};
}
