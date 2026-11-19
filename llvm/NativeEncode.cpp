#include "llvm/ADT/APInt.h"
#include "llvm/ADT/SmallVector.h"
#include "llvm/ADT/StringRef.h"
#include "llvm/Config/llvm-config.h"
#include "llvm/IR/IRBuilder.h"
#include "llvm/IR/InstIterator.h"
#include "llvm/IR/MDBuilder.h"
#include "llvm/IR/PassManager.h"
#include "llvm/Passes/PassBuilder.h"
#include "llvm/Plugins/PassPlugin.h"
#include "llvm/Support/raw_ostream.h"
#include <cstdint>
#include <string>
#include <utility>

namespace {

enum class Transform { Encode, Diversify };

struct Options {
  Transform Kind = Transform::Encode;
  uint64_t Seed = 0;
  unsigned MaxSites = 8;
  std::string Prefix;
};

bool isPrefixCharacter(char C) {
  return (C >= 'a' && C <= 'z') || (C >= 'A' && C <= 'Z') ||
         (C >= '0' && C <= '9') || C == '_' || C == '.' || C == '$';
}

bool readOptions(llvm::StringRef Name, llvm::StringRef PassName, Options &Config) {
  if (!Name.consume_front(PassName))
    return false;
  if (Name.empty())
    return true;
  if (!Name.consume_front("<") || !Name.consume_back(">") ||
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

uint64_t nextRandom(uint64_t &State) {
  State += UINT64_C(0x9e3779b97f4a7c15);
  uint64_t Mixed = State;
  Mixed = (Mixed ^ (Mixed >> 30)) * UINT64_C(0xbf58476d1ce4e5b9);
  Mixed = (Mixed ^ (Mixed >> 27)) * UINT64_C(0x94d049bb133111eb);
  return Mixed ^ (Mixed >> 31);
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

llvm::Value *buildEncoding(llvm::IRBuilder<> &Builder,
                           const llvm::BinaryOperator &Operation, uint64_t &State) {
  auto *Type = llvm::cast<llvm::IntegerType>(Operation.getType());
  unsigned Width = Type->getBitWidth();
  llvm::APInt Factor =
      llvm::APInt(64, nextRandom(State) | UINT64_C(1)).zextOrTrunc(Width);
  if (Factor.isOne() || Factor.isAllOnes())
    Factor = llvm::APInt(Width, 3);

  auto *Scale = llvm::ConstantInt::get(Type, Factor);
  auto *Inverse = llvm::ConstantInt::get(Type, modularInverse(Factor));
  // Work in Z/(2^N). The encoding is linear, and the odd scale is
  // invertible. Each original operand is used once.
  auto *Left =
      Builder.CreateMul(Operation.getOperand(0), Scale, "native.encode.left");
  auto *Right =
      Builder.CreateMul(Operation.getOperand(1), Scale, "native.encode.right");
  auto *Encoded = Builder.CreateBinOp(Operation.getOpcode(), Left, Right,
                                      "native.encode.combined");
  return Builder.CreateMul(Encoded, Inverse, "native.encode.result");
}

bool canDiversify(const llvm::Function &Function) {
  if (Function.hasPersonalityFn() || Function.isPresplitCoroutine() ||
      Function.hasFnAttribute(llvm::Attribute::Convergent) ||
      Function.hasFnAttribute(llvm::Attribute::Naked))
    return false;
  for (const llvm::Instruction &Instruction : llvm::instructions(Function)) {
    if (llvm::isa<llvm::CallBrInst, llvm::IndirectBrInst>(Instruction))
      return false;
    if (auto *Call = llvm::dyn_cast<llvm::CallBase>(&Instruction);
        Call && Call->isConvergent())
      return false;
  }
  return true;
}

llvm::Argument *findGuard(const llvm::BinaryOperator &Operation) {
  if (Operation.getParent()->hasAddressTaken())
    return nullptr;
  // A branch on poison or undef would add undefined behavior. A noundef
  // argument supplies a defined branch input for every valid call.
  for (llvm::Value *Operand : Operation.operands()) {
    auto *Argument = llvm::dyn_cast<llvm::Argument>(Operand);
    if (Argument && Argument->hasAttribute(llvm::Attribute::NoUndef))
      return Argument;
  }
  return nullptr;
}

llvm::PHINode *diversify(llvm::BinaryOperator &Operation, llvm::Argument &Guard,
                        uint64_t &State) {
  auto *Initial = Operation.getParent();
  auto *Join = Initial->splitBasicBlock(Operation.getIterator(),
                                        "native.diversify.join");
  auto *Function = Initial->getParent();
  auto &Context = Function->getContext();
  auto *Plain =
      llvm::BasicBlock::Create(Context, "native.diversify.plain", Function, Join);
  auto *Encoded =
      llvm::BasicBlock::Create(Context, "native.diversify.encoded", Function, Join);
  auto *Type = llvm::cast<llvm::IntegerType>(Operation.getType());

  Initial->getTerminator()->eraseFromParent();
  llvm::IRBuilder<> Builder(Initial);
  Builder.SetCurrentDebugLocation(Operation.getDebugLoc());
  unsigned Shift = nextRandom(State) % (Type->getBitWidth() - 3);
  auto *Mask = llvm::ConstantInt::get(Type, UINT64_C(15) << Shift);
  auto *Masked = Builder.CreateAnd(&Guard, Mask);
  // Four zero bits occur once per sixteen uniformly distributed inputs.
  // Keep the inexpensive path favored when lowering the control flow.
  auto *Weights = llvm::MDBuilder(Context).createBranchWeights(1, 15);
  Builder.CreateCondBr(Builder.CreateIsNull(Masked), Encoded, Plain, Weights);

  Builder.SetInsertPoint(Plain);
  Builder.SetCurrentDebugLocation(Operation.getDebugLoc());
  auto *PlainResult = Builder.CreateBinOp(Operation.getOpcode(),
                                          Operation.getOperand(0),
                                          Operation.getOperand(1));
  Builder.CreateBr(Join);

  Builder.SetInsertPoint(Encoded);
  Builder.SetCurrentDebugLocation(Operation.getDebugLoc());
  auto *EncodedResult = buildEncoding(Builder, Operation, State);
  Builder.CreateBr(Join);

  Builder.SetInsertPoint(&Operation);
  auto *Result = Builder.CreatePHI(Type, 2);
  Result->addIncoming(PlainResult, Plain);
  Result->addIncoming(EncodedResult, Encoded);
  return Result;
}

bool rewriteFunction(llvm::Function &Function, const Options &Config) {
  if (Config.MaxSites == 0 || !Function.getName().starts_with(Config.Prefix) ||
      Function.hasFnAttribute(llvm::Attribute::OptimizeNone))
    return false;
  bool UseBranches = Config.Kind == Transform::Diversify;
  if (UseBranches && !canDiversify(Function))
    return false;

  struct Site {
    llvm::BinaryOperator *Operation;
    llvm::Argument *Guard;
  };
  llvm::SmallVector<Site, 8> Sites;
  for (llvm::Instruction &Instruction : llvm::instructions(Function)) {
    auto *Operation = llvm::dyn_cast<llvm::BinaryOperator>(&Instruction);
    if (!Operation || !canEncode(*Operation))
      continue;
    auto *Guard = UseBranches ? findGuard(*Operation) : nullptr;
    if (UseBranches && !Guard)
      continue;
    Sites.push_back({Operation, Guard});
    if (Sites.size() == Config.MaxSites)
      break;
  }

  uint64_t State = UINT64_C(0xcbf29ce484222325);
  for (unsigned char C : Function.getName()) {
    State ^= C;
    State *= UINT64_C(0x100000001b3);
  }
  State ^= Config.Seed;

  for (const auto &[Operation, Guard] : Sites) {
    llvm::IRBuilder<> Builder(Operation);
    Builder.SetCurrentDebugLocation(Operation->getDebugLoc());
    auto *Result = Guard ? diversify(*Operation, *Guard, State)
                         : buildEncoding(Builder, *Operation, State);
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
      Changed |= rewriteFunction(Function, Config);
    return Changed ? llvm::PreservedAnalyses::none()
                   : llvm::PreservedAnalyses::all();
  }
};

void registerPass(llvm::PassBuilder &Builder) {
  Builder.registerPipelineParsingCallback(
      [](llvm::StringRef Name, llvm::ModulePassManager &Manager,
         llvm::ArrayRef<llvm::PassBuilder::PipelineElement> InnerPipeline) {
        Options Config;
        llvm::StringRef Pass;
        if (Name == "native-encode" || Name.starts_with("native-encode<")) {
          Pass = "native-encode";
        } else if (Name == "native-diversify" ||
                   Name.starts_with("native-diversify<")) {
          Pass = "native-diversify";
          Config.Kind = Transform::Diversify;
          Config.MaxSites = 2;
        } else {
          return false;
        }
        if (!InnerPipeline.empty() || !readOptions(Name, Pass, Config)) {
          llvm::errs() << "invalid " << Pass << " options; expected "
                       << Pass << "<seed=UINT64;max=UINT;prefix=NAME>\n";
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
