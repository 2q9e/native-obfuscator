#include "llvm/ADT/APInt.h"
#include "llvm/ADT/SmallVector.h"
#include "llvm/ADT/StringRef.h"
#include "llvm/Analysis/ValueTracking.h"
#include "llvm/Config/llvm-config.h"
#include "llvm/IR/IRBuilder.h"
#include "llvm/IR/InstIterator.h"
#include "llvm/IR/MDBuilder.h"
#include "llvm/IR/PassManager.h"
#include "llvm/Passes/PassBuilder.h"
#include "llvm/Plugins/PassPlugin.h"
#include "llvm/Support/ErrorHandling.h"
#include "llvm/Support/raw_ostream.h"
#include <cstdint>
#include <optional>
#include <string>
#include <utility>

namespace {

enum class Transform { Encode, Diversify, Mix, Route };

llvm::StringRef passName(Transform Kind) {
  switch (Kind) {
  case Transform::Encode:
    return "native-encode";
  case Transform::Diversify:
    return "native-diversify";
  case Transform::Mix:
    return "native-mix";
  case Transform::Route:
    return "native-route";
  }
  llvm_unreachable("unknown native transform");
}

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

bool readOptions(llvm::StringRef Name, llvm::StringRef PassName,
                 Options &Config) {
  if (!Name.consume_front(PassName))
    return false;
  if (Name.empty())
    return true;
  if (!Name.consume_front("<") || !Name.consume_back(">") || Name.empty() ||
      Name.ends_with(";"))
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

bool isArithmetic(unsigned Opcode) {
  return Opcode == llvm::Instruction::Add || Opcode == llvm::Instruction::Sub ||
         Opcode == llvm::Instruction::Mul;
}

bool isBitwise(unsigned Opcode) {
  return Opcode == llvm::Instruction::And || Opcode == llvm::Instruction::Or ||
         Opcode == llvm::Instruction::Xor;
}

bool hasSupportedWidth(const llvm::BinaryOperator &Operation) {
  auto *Type = llvm::dyn_cast<llvm::IntegerType>(Operation.getType());
  if (!Type)
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

bool canEncode(const llvm::BinaryOperator &Operation) {
  return isArithmetic(Operation.getOpcode()) && hasSupportedWidth(Operation) &&
         !Operation.hasNoUnsignedWrap() && !Operation.hasNoSignedWrap();
}

llvm::APInt encodingFactor(unsigned Width, uint64_t &State) {
  llvm::APInt Factor =
      llvm::APInt(64, nextRandom(State) | UINT64_C(1)).zextOrTrunc(Width);
  if (Factor.isOne() || Factor.isAllOnes())
    Factor = llvm::APInt(Width, 3);
  return Factor;
}

llvm::Value *buildEncoding(llvm::IRBuilder<> &Builder,
                           const llvm::BinaryOperator &Operation,
                           const llvm::APInt &Factor) {
  auto *Type = llvm::cast<llvm::IntegerType>(Operation.getType());
  auto *Scale = llvm::ConstantInt::get(Type, Factor);
  auto *Inverse = llvm::ConstantInt::get(Type, modularInverse(Factor));
  // Work in Z/(2^N), where an odd scale has a modular inverse. For add/sub,
  // scale both inputs; for mul, scale one input. Each original operand is
  // used once and the final inverse restores the original result.
  auto *Left =
      Builder.CreateMul(Operation.getOperand(0), Scale, "native.encode.left");
  llvm::Value *Encoded;
  if (Operation.getOpcode() == llvm::Instruction::Mul) {
    Encoded = Builder.CreateMul(Left, Operation.getOperand(1),
                                "native.encode.combined");
  } else {
    auto *Right = Builder.CreateMul(Operation.getOperand(1), Scale,
                                    "native.encode.right");
    Encoded = Builder.CreateBinOp(Operation.getOpcode(), Left, Right,
                                  "native.encode.combined");
  }
  return Builder.CreateMul(Encoded, Inverse, "native.encode.result");
}

llvm::Value *buildOffsetProduct(llvm::IRBuilder<> &Builder, llvm::Value *Left,
                                llvm::Value *Right, const llvm::APInt &Amount,
                                llvm::StringRef Prefix) {
  auto *Offset = llvm::ConstantInt::get(Left->getType(), Amount);
  // (Left + Offset) * Right - Offset * Right == Left * Right modulo 2^N.
  auto *Shifted = Builder.CreateAdd(Left, Offset, Prefix + ".shifted");
  auto *Product = Builder.CreateMul(Shifted, Right, Prefix + ".product");
  auto *Adjustment = Builder.CreateMul(Offset, Right, Prefix + ".adjustment");
  return Builder.CreateSub(Product, Adjustment);
}

llvm::Value *buildSplitProduct(llvm::IRBuilder<> &Builder, llvm::Value *Left,
                               llvm::Value *Right, const llvm::APInt &Mask) {
  auto *Selected = llvm::ConstantInt::get(Right->getType(), Mask);
  auto *Complement = llvm::ConstantInt::get(Right->getType(), ~Mask);
  // The selected contribution is even. Toggling Left's high bit changes
  // the first product by a multiple of 2^N.
  auto *Sign = llvm::ConstantInt::get(Left->getType(),
                                      llvm::APInt::getSignMask(Mask.getBitWidth()));
  auto *Shifted = Builder.CreateXor(Left, Sign, "native.mix.shifted");
  auto *FirstBits = Builder.CreateAnd(Right, Selected, "native.mix.selected");
  auto *OtherBits = Builder.CreateAnd(Right, Complement, "native.mix.complement");
  auto *First = Builder.CreateMul(Shifted, FirstBits, "native.mix.first");
  auto *Other = Builder.CreateMul(Left, OtherBits, "native.mix.other");
  return Builder.CreateAdd(First, Other);
}

llvm::Value *buildMixing(llvm::IRBuilder<> &Builder,
                         const llvm::BinaryOperator &Operation,
                         uint64_t &State) {
  auto *Left = Operation.getOperand(0);
  auto *Right = Operation.getOperand(1);
  auto *Type = llvm::cast<llvm::IntegerType>(Operation.getType());
  bool Alternate = nextRandom(State) & 1;

  // Each identity is an equality on N-bit words, with arithmetic modulo 2^N.
  // Some identities use both inputs twice, so their operands must be defined.
  switch (Operation.getOpcode()) {
  case llvm::Instruction::Add: {
    auto *Common = Builder.CreateAnd(Left, Right, "native.mix.common");
    if (Alternate) {
      auto *Different = Builder.CreateXor(Left, Right, "native.mix.different");
      auto *Carry = Builder.CreateShl(Common, 1, "native.mix.carry");
      return Builder.CreateAdd(Different, Carry);
    }
    auto *Union = Builder.CreateOr(Left, Right, "native.mix.union");
    return Builder.CreateAdd(Union, Common);
  }
  case llvm::Instruction::Sub: {
    auto *Complement = Builder.CreateNot(Left, "native.mix.complement");
    if (Alternate) {
      auto *Sum = Builder.CreateAdd(Complement, Right, "native.mix.sum");
      return Builder.CreateNot(Sum);
    }
    auto *Different = Builder.CreateXor(Left, Right, "native.mix.different");
    auto *Borrow = Builder.CreateAnd(Complement, Right, "native.mix.borrow");
    auto *Carry = Builder.CreateShl(Borrow, 1, "native.mix.carry");
    return Builder.CreateSub(Different, Carry);
  }
  case llvm::Instruction::Mul: {
    if (Alternate) {
      unsigned Width = Type->getBitWidth();
      auto Mask = llvm::APInt(Width, nextRandom(State));
      Mask.clearBit(0);
      if (Mask.isZero())
        Mask = llvm::APInt(Width, UINT64_C(0x5555555555555554));
      return buildSplitProduct(Builder, Left, Right, Mask);
    }
    return buildOffsetProduct(Builder, Left, Right,
                              encodingFactor(Type->getBitWidth(), State),
                              "native.mix");
  }
  case llvm::Instruction::And: {
    auto *Union = Builder.CreateOr(Left, Right, "native.mix.union");
    if (Alternate) {
      auto *Different = Builder.CreateXor(Left, Right, "native.mix.different");
      return Builder.CreateXor(Union, Different);
    }
    auto *Sum = Builder.CreateAdd(Left, Right, "native.mix.sum");
    return Builder.CreateSub(Sum, Union);
  }
  case llvm::Instruction::Or: {
    auto *Common = Builder.CreateAnd(Left, Right, "native.mix.common");
    if (Alternate) {
      auto *Different = Builder.CreateXor(Left, Right, "native.mix.different");
      return Builder.CreateOr(Different, Common);
    }
    auto *Sum = Builder.CreateAdd(Left, Right, "native.mix.sum");
    return Builder.CreateSub(Sum, Common);
  }
  case llvm::Instruction::Xor: {
    auto *Common = Builder.CreateAnd(Left, Right, "native.mix.common");
    if (Alternate) {
      auto *Sum = Builder.CreateAdd(Left, Right, "native.mix.sum");
      auto *Carry = Builder.CreateShl(Common, 1, "native.mix.carry");
      return Builder.CreateSub(Sum, Carry);
    }
    auto *Union = Builder.CreateOr(Left, Right, "native.mix.union");
    return Builder.CreateSub(Union, Common);
  }
  default:
    llvm_unreachable("unsupported native-mix operation");
  }
}

bool canDiversify(const llvm::Function &Function, bool IsRoute) {
  if (Function.hasPersonalityFn() || Function.isPresplitCoroutine() ||
      Function.hasFnAttribute(llvm::Attribute::Convergent) ||
      Function.hasFnAttribute(llvm::Attribute::Naked))
    return false;
  if (IsRoute && (Function.hasGC() ||
                  Function.hasFnAttribute(llvm::Attribute::ReturnsTwice)))
    return false;
  for (const llvm::Instruction &Instruction : llvm::instructions(Function)) {
    if (llvm::isa<llvm::CallBrInst, llvm::IndirectBrInst>(Instruction))
      return false;
    if (auto *Call = llvm::dyn_cast<llvm::CallBase>(&Instruction);
        Call && (Call->isConvergent() ||
                 (IsRoute && Call->hasFnAttr(llvm::Attribute::ReturnsTwice))))
      return false;
  }
  return true;
}

std::optional<unsigned> findGuard(const llvm::BinaryOperator &Operation,
                                  bool IsRoute) {
  if (Operation.getParent()->hasAddressTaken())
    return std::nullopt;
  // A branch on poison or undef would add undefined behavior. A noundef
  // argument supplies a defined branch input for every valid call. Route can
  // also use a nonconstant operand proved defined by LLVM ValueTracking.
  for (unsigned Index = 0; Index < Operation.getNumOperands(); ++Index) {
    auto *Operand = Operation.getOperand(Index);
    if (IsRoute) {
      if (!llvm::isa<llvm::Constant>(Operand) &&
          llvm::isGuaranteedNotToBeUndefOrPoison(Operand))
        return Index;
      continue;
    }
    auto *Argument = llvm::dyn_cast<llvm::Argument>(Operand);
    if (Argument && Argument->hasAttribute(llvm::Attribute::NoUndef))
      return Index;
  }
  return std::nullopt;
}

llvm::PHINode *branchOperation(llvm::BinaryOperator &Operation,
                               llvm::Value &Guard, uint64_t &State,
                               bool IsRoute) {
  auto *Initial = Operation.getParent();
  auto *Join = Initial->splitBasicBlock(Operation.getIterator(),
                                        IsRoute ? "native.route.join"
                                                : "native.diversify.join");
  auto *Function = Initial->getParent();
  auto &Context = Function->getContext();
  auto *First = llvm::BasicBlock::Create(
      Context, IsRoute ? "native.route.first" : "native.diversify.plain",
      Function, Join);
  auto *Second = llvm::BasicBlock::Create(
      Context, IsRoute ? "native.route.second" : "native.diversify.encoded",
      Function, Join);
  auto *Type = llvm::cast<llvm::IntegerType>(Operation.getType());
  unsigned Width = Type->getBitWidth();

  Initial->getTerminator()->eraseFromParent();
  llvm::IRBuilder<> Builder(Initial);
  Builder.SetCurrentDebugLocation(Operation.getDebugLoc());
  unsigned Shift = nextRandom(State) % (IsRoute ? Width : Width - 3);
  auto *Mask = llvm::ConstantInt::get(
      Type, (IsRoute ? UINT64_C(1) : UINT64_C(15)) << Shift);
  auto *Masked = Builder.CreateAnd(&Guard, Mask);
  // Four zero bits occur once per sixteen uniformly distributed inputs.
  // Keep the inexpensive path favored when lowering the control flow.
  auto *Weights =
      IsRoute ? nullptr : llvm::MDBuilder(Context).createBranchWeights(1, 15);
  Builder.CreateCondBr(Builder.CreateIsNull(Masked), Second, First, Weights);

  auto BuildEncoded = [&](const llvm::APInt &Factor) {
    if (IsRoute && Operation.getOpcode() == llvm::Instruction::Mul) {
      // The guard is defined. Use the other operand once per executed path.
      auto *Other =
          Operation.getOperand(Operation.getOperand(0) == &Guard ? 1 : 0);
      return buildOffsetProduct(Builder, Other, &Guard, Factor, "native.route");
    }
    return buildEncoding(Builder, Operation, Factor);
  };

  Builder.SetInsertPoint(First);
  Builder.SetCurrentDebugLocation(Operation.getDebugLoc());
  llvm::APInt FirstFactor =
      IsRoute ? encodingFactor(Width, State) : llvm::APInt(Width, 1);
  auto *FirstResult = IsRoute ? BuildEncoded(FirstFactor)
                              : Builder.CreateBinOp(Operation.getOpcode(),
                                                    Operation.getOperand(0),
                                                    Operation.getOperand(1));
  Builder.CreateBr(Join);

  Builder.SetInsertPoint(Second);
  Builder.SetCurrentDebugLocation(Operation.getDebugLoc());
  auto SecondFactor = encodingFactor(Width, State);
  if (SecondFactor == FirstFactor)
    SecondFactor = llvm::APInt(Width, FirstFactor == 3 ? 5 : 3);
  auto *SecondResult = BuildEncoded(SecondFactor);
  Builder.CreateBr(Join);

  Builder.SetInsertPoint(&Operation);
  auto *Result = Builder.CreatePHI(Type, 2);
  Result->addIncoming(FirstResult, First);
  Result->addIncoming(SecondResult, Second);
  return Result;
}

struct RewriteStatistics {
  unsigned CandidateSites = 0;
  unsigned FlagFilteredSites = 0;
  unsigned TypeFilteredSites = 0;
  unsigned UndefinedOperandSites = 0;
  unsigned UnsafeBlockSites = 0;
  unsigned GuardFilteredSites = 0;
  unsigned EligibleSites = 0;
  unsigned CapSkippedSites = 0;
  unsigned TransformedSites = 0;
  unsigned PrefixFilteredFunctions = 0;
  unsigned OptimizeNoneFunctions = 0;
  unsigned UnsafeControlFlowFunctions = 0;
  unsigned ChangedFunctions = 0;
};

void writeSiteRejections(llvm::raw_ostream &Stream, Transform Kind,
                         const RewriteStatistics &Statistics,
                         const RewriteStatistics &Before = {}) {
  if (Kind == Transform::Mix || Kind == Transform::Route)
    Stream << " skipped_flags="
           << Statistics.FlagFilteredSites - Before.FlagFilteredSites
           << " skipped_type="
           << Statistics.TypeFilteredSites - Before.TypeFilteredSites;
  if (Kind == Transform::Mix)
    Stream << " skipped_undefined="
           << Statistics.UndefinedOperandSites - Before.UndefinedOperandSites;
  if (Kind == Transform::Route)
    Stream << " skipped_unsafe_blocks="
           << Statistics.UnsafeBlockSites - Before.UnsafeBlockSites;
}

bool rewriteFunction(llvm::Function &Function, const Options &Config,
                     RewriteStatistics &Statistics) {
  if (!Function.getName().starts_with(Config.Prefix)) {
    ++Statistics.PrefixFilteredFunctions;
    return false;
  }
  if (Function.hasFnAttribute(llvm::Attribute::OptimizeNone)) {
    ++Statistics.OptimizeNoneFunctions;
    return false;
  }
  bool IsMix = Config.Kind == Transform::Mix;
  bool IsRoute = Config.Kind == Transform::Route;
  bool UseBranches = IsRoute || Config.Kind == Transform::Diversify;
  if ((UseBranches && !canDiversify(Function, IsRoute)) ||
      (IsMix && Function.hasFnAttribute(llvm::Attribute::Naked))) {
    ++Statistics.UnsafeControlFlowFunctions;
    return false;
  }

  struct Site {
    llvm::BinaryOperator *Operation;
    std::optional<unsigned> GuardOperand;
  };
  llvm::SmallVector<Site, 8> Sites;
  for (llvm::Instruction &Instruction : llvm::instructions(Function)) {
    auto *Operation = llvm::dyn_cast<llvm::BinaryOperator>(&Instruction);
    if (!Operation)
      continue;
    if (IsMix || IsRoute) {
      unsigned Opcode = Operation->getOpcode();
      if (!isArithmetic(Opcode) && !(IsMix && isBitwise(Opcode)))
        continue;
    } else if (!canEncode(*Operation)) {
      continue;
    }
    ++Statistics.CandidateSites;

    if (IsMix || IsRoute) {
      if (!hasSupportedWidth(*Operation)) {
        ++Statistics.TypeFilteredSites;
        continue;
      }
      if (Operation->hasPoisonGeneratingFlags()) {
        ++Statistics.FlagFilteredSites;
        continue;
      }
    }
    if (IsMix &&
        (!llvm::isGuaranteedNotToBeUndefOrPoison(Operation->getOperand(0)) ||
         !llvm::isGuaranteedNotToBeUndefOrPoison(Operation->getOperand(1)))) {
      ++Statistics.UndefinedOperandSites;
      continue;
    }
    if (IsRoute && (Operation->getParent()->isEHPad() ||
                    Operation->getParent()->hasAddressTaken())) {
      ++Statistics.UnsafeBlockSites;
      continue;
    }
    auto GuardOperand =
        UseBranches ? findGuard(*Operation, IsRoute) : std::nullopt;
    if (UseBranches && !GuardOperand) {
      ++Statistics.GuardFilteredSites;
      continue;
    }
    ++Statistics.EligibleSites;
    if (Sites.size() == Config.MaxSites) {
      ++Statistics.CapSkippedSites;
      continue;
    }
    Sites.push_back({Operation, GuardOperand});
  }

  uint64_t State = UINT64_C(0xcbf29ce484222325);
  for (unsigned char C : Function.getName()) {
    State ^= C;
    State *= UINT64_C(0x100000001b3);
  }
  State ^= Config.Seed;

  for (const auto &[Operation, GuardOperand] : Sites) {
    llvm::IRBuilder<> Builder(Operation);
    Builder.SetCurrentDebugLocation(Operation->getDebugLoc());
    llvm::Value *Result;
    if (GuardOperand) {
      // Earlier ordered rewrites can replace an operand of this site.
      Result = branchOperation(
          *Operation, *Operation->getOperand(*GuardOperand), State, IsRoute);
    } else if (IsMix) {
      Result = buildMixing(Builder, *Operation, State);
    } else {
      auto *Type = llvm::cast<llvm::IntegerType>(Operation->getType());
      Result = buildEncoding(Builder, *Operation,
                             encodingFactor(Type->getBitWidth(), State));
    }
    if (auto *ResultInstruction = llvm::dyn_cast<llvm::Instruction>(Result))
      ResultInstruction->takeName(Operation);
    Operation->replaceAllUsesWith(Result);
    Operation->eraseFromParent();
  }
  Statistics.TransformedSites += static_cast<unsigned>(Sites.size());
  if (!Sites.empty())
    ++Statistics.ChangedFunctions;
  return !Sites.empty();
}

class NativeEncode : public llvm::OptionalPassInfoMixin<NativeEncode> {
  Options Config;

public:
  explicit NativeEncode(Options Config) : Config(std::move(Config)) {}

  llvm::PreservedAnalyses run(llvm::Module &Module,
                              llvm::ModuleAnalysisManager &) {
    bool Changed = false;
    RewriteStatistics Statistics;
    for (llvm::Function &Function : Module) {
      if (!Function.isDeclaration()) {
        RewriteStatistics Before = Statistics;
        Changed |= rewriteFunction(Function, Config, Statistics);
        if (Function.getName().starts_with(Config.Prefix)) {
          auto &Stream = llvm::errs();
          Stream << "native-obfuscator-function: pass=" << passName(Config.Kind)
                 << " function=" << Function.getName() << " candidates="
                 << Statistics.CandidateSites - Before.CandidateSites
                 << " eligible="
                 << Statistics.EligibleSites - Before.EligibleSites
                 << " transformed="
                 << Statistics.TransformedSites - Before.TransformedSites
                 << " skipped_guard="
                 << Statistics.GuardFilteredSites - Before.GuardFilteredSites
                 << " skipped_cap="
                 << Statistics.CapSkippedSites - Before.CapSkippedSites
                 << " skipped_optnone="
                 << Statistics.OptimizeNoneFunctions -
                        Before.OptimizeNoneFunctions
                 << " skipped_unsafe_control_flow="
                 << Statistics.UnsafeControlFlowFunctions -
                        Before.UnsafeControlFlowFunctions;
          writeSiteRejections(Stream, Config.Kind, Statistics, Before);
          Stream << '\n';
        }
      }
    }
    auto &Stream = llvm::errs();
    Stream << "native-obfuscator: " << passName(Config.Kind)
           << " candidates=" << Statistics.CandidateSites
           << " eligible=" << Statistics.EligibleSites
           << " transformed=" << Statistics.TransformedSites
           << " skipped_guard=" << Statistics.GuardFilteredSites
           << " skipped_cap=" << Statistics.CapSkippedSites
           << " skipped_prefix_functions=" << Statistics.PrefixFilteredFunctions
           << " skipped_optnone_functions=" << Statistics.OptimizeNoneFunctions
           << " skipped_unsafe_control_flow_functions="
           << Statistics.UnsafeControlFlowFunctions
           << " changed_functions=" << Statistics.ChangedFunctions
           << " max_sites_per_function=" << Config.MaxSites
           << " seed=" << Config.Seed;
    writeSiteRejections(Stream, Config.Kind, Statistics);
    Stream << '\n';
    return Changed ? llvm::PreservedAnalyses::none()
                   : llvm::PreservedAnalyses::all();
  }
};

void registerPass(llvm::PassBuilder &Builder) {
  Builder.registerPipelineParsingCallback(
      [](llvm::StringRef Name, llvm::ModulePassManager &Manager,
         llvm::ArrayRef<llvm::PassBuilder::PipelineElement> InnerPipeline) {
        Options Config;
        llvm::StringRef Pass = Name.split('<').first;
        if (Pass == passName(Transform::Diversify)) {
          Config.Kind = Transform::Diversify;
          Config.MaxSites = 2;
        } else if (Pass == passName(Transform::Mix)) {
          Config.Kind = Transform::Mix;
          Config.MaxSites = 4;
        } else if (Pass == passName(Transform::Route)) {
          Config.Kind = Transform::Route;
          Config.MaxSites = 1;
        } else if (Pass != passName(Transform::Encode)) {
          return false;
        }
        if (!InnerPipeline.empty() || !readOptions(Name, Pass, Config)) {
          llvm::errs() << "invalid " << Pass << " options; expected " << Pass
                       << "<seed=UINT64;max=UINT;prefix=NAME>\n";
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
