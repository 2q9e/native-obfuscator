#include "NativeTransforms.h"

#include "llvm/Analysis/ValueTracking.h"
#include "llvm/IR/InstIterator.h"

#include <optional>

namespace nativeobfuscator {

uint64_t nextRandom(uint64_t &State) {
  State += UINT64_C(0x9e3779b97f4a7c15);
  uint64_t Mixed = State;
  Mixed = (Mixed ^ (Mixed >> 30)) * UINT64_C(0xbf58476d1ce4e5b9);
  Mixed = (Mixed ^ (Mixed >> 27)) * UINT64_C(0x94d049bb133111eb);
  return Mixed ^ (Mixed >> 31);
}

uint64_t functionState(const llvm::Function &Function, uint64_t Seed) {
  uint64_t State = UINT64_C(0xcbf29ce484222325);
  for (unsigned char C : Function.getName()) {
    State ^= C;
    State *= UINT64_C(0x100000001b3);
  }
  return State ^ Seed;
}

static llvm::APInt modularInverse(const llvm::APInt &Factor) {
  unsigned Width = Factor.getBitWidth();
  llvm::APInt Inverse(Width, 1);
  llvm::APInt Two(Width, 2);
  // An odd factor is its own inverse modulo two. Each Newton step
  // doubles the number of correct low bits.
  for (unsigned Bits = 1; Bits < Width; Bits *= 2)
    Inverse *= Two - Factor * Inverse;
  return Inverse;
}

static bool isArithmetic(unsigned Opcode) {
  return Opcode == llvm::Instruction::Add || Opcode == llvm::Instruction::Sub ||
         Opcode == llvm::Instruction::Mul;
}

static bool isBitwise(unsigned Opcode) {
  return Opcode == llvm::Instruction::And || Opcode == llvm::Instruction::Or ||
         Opcode == llvm::Instruction::Xor;
}

bool hasSupportedWidth(llvm::Type *ValueType) {
  auto *Type = llvm::dyn_cast<llvm::IntegerType>(ValueType);
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

static llvm::Value *buildSplitProduct(llvm::IRBuilder<> &Builder, llvm::Value *Left,
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

static llvm::Value *buildMixing(llvm::IRBuilder<> &Builder,
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
      auto Mask = llvm::APInt(64, nextRandom(State)).zextOrTrunc(Width);
      Mask.clearBit(0);
      if (Mask.isZero())
        Mask = llvm::APInt(64, UINT64_C(0x5555555555555554)).zextOrTrunc(Width);
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

static std::optional<unsigned> findGuard(const llvm::BinaryOperator &Operation,
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

bool rewriteIntegerOperations(llvm::Function &Function,
                              const TransformOptions &Config,
                              RewriteStatistics &Statistics) {
  bool IsMix = Config.Kind == Transform::Mix;
  bool IsRoute = Config.Kind == Transform::Route;
  bool UseBranches = IsRoute || Config.Kind == Transform::Diversify;
  if ((UseBranches && !canDiversify(Function, IsRoute)) ||
      Function.hasFnAttribute(llvm::Attribute::Naked)) {
    ++Statistics.UnsafeControlFlowFunctions;
    return false;
  }

  struct Site {
    llvm::BinaryOperator *Operation;
    std::optional<unsigned> GuardOperand;
  };
  // Splitting the entry block must leave static allocas and entry-only
  // intrinsics in that block. Sites among the entry setup stay unchanged.
  auto *LastSetup = UseBranches ? lastEntrySetup(Function) : nullptr;
  llvm::SmallVector<Site, 8> Sites;
  for (llvm::Instruction &Instruction : llvm::instructions(Function)) {
    auto *Operation = llvm::dyn_cast<llvm::BinaryOperator>(&Instruction);
    if (!Operation)
      continue;
    unsigned Opcode = Operation->getOpcode();
    if (!isArithmetic(Opcode) && !(IsMix && isBitwise(Opcode)))
      continue;
    ++Statistics.CandidateSites;

    if (!hasSupportedWidth(Operation->getType())) {
      ++Statistics.TypeFilteredSites;
      continue;
    }
    if (Operation->hasPoisonGeneratingFlags()) {
      ++Statistics.FlagFilteredSites;
      continue;
    }
    if (IsMix &&
        (!llvm::isGuaranteedNotToBeUndefOrPoison(Operation->getOperand(0)) ||
         !llvm::isGuaranteedNotToBeUndefOrPoison(Operation->getOperand(1)))) {
      ++Statistics.UndefinedOperandSites;
      continue;
    }
    if (UseBranches &&
        (Operation->getParent()->isEHPad() ||
         Operation->getParent()->hasAddressTaken() ||
         (LastSetup && Operation->getParent() == LastSetup->getParent() &&
          Operation->comesBefore(LastSetup)))) {
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

  uint64_t State = functionState(Function, Config.Seed);

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

} // namespace nativeobfuscator
