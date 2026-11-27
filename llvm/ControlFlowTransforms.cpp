#include "NativeTransforms.h"

#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/STLExtras.h"
#include "llvm/Analysis/ValueTracking.h"
#include "llvm/Config/llvm-config.h"
#include "llvm/IR/InstIterator.h"
#include "llvm/IR/IntrinsicInst.h"
#include "llvm/IR/MDBuilder.h"
#include "llvm/Transforms/Scalar/Reg2Mem.h"

namespace nativeobfuscator {

#if LLVM_VERSION_MAJOR >= 23
using ConditionalBranch = llvm::CondBrInst;
#else
using ConditionalBranch = llvm::BranchInst;
#endif

template <typename InstructionType>
static auto *conditionalBranch(InstructionType *Instruction) {
  auto *Branch = llvm::dyn_cast<ConditionalBranch>(Instruction);
  return Branch && Branch->getNumSuccessors() == 2 ? Branch : nullptr;
}

static bool isBranch(const llvm::Instruction *Instruction) {
#if LLVM_VERSION_MAJOR >= 23
  return llvm::isa<llvm::CondBrInst, llvm::UncondBrInst>(Instruction);
#else
  return llvm::isa<llvm::BranchInst>(Instruction);
#endif
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

static bool isEntrySetupIntrinsic(const llvm::Instruction &Instruction) {
  auto *Call = llvm::dyn_cast<llvm::IntrinsicInst>(&Instruction);
  return Call && (Call->getIntrinsicID() == llvm::Intrinsic::localescape ||
                  Call->getIntrinsicID() == llvm::Intrinsic::gcroot);
}

const llvm::Instruction *lastEntrySetup(const llvm::Function &Function) {
  const llvm::Instruction *LastSetup = nullptr;
  for (const auto &Instruction : Function.getEntryBlock()) {
    auto *Alloca = llvm::dyn_cast<llvm::AllocaInst>(&Instruction);
    if ((Alloca && Alloca->isStaticAlloca()) ||
        isEntrySetupIntrinsic(Instruction))
      LastSetup = &Instruction;
  }
  return LastSetup;
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

enum class DispatchSafety { Safe, UnsupportedControlFlow, UnsupportedType };

static DispatchSafety assessDispatch(const llvm::Function &Function) {
  if (!canDiversify(Function, true) ||
      Function.hasFnAttribute(llvm::Attribute::Speculatable))
    return DispatchSafety::UnsupportedControlFlow;
  for (const auto &Block : Function) {
    auto *End = Block.getTerminator();
    if (Block.hasAddressTaken() || Block.isEHPad() ||
        (!isBranch(End) && !llvm::isa<llvm::ReturnInst, llvm::UnreachableInst>(End)))
      return DispatchSafety::UnsupportedControlFlow;
    if (auto *Branch = conditionalBranch(Block.getTerminator());
        Branch && Branch->getSuccessor(0) == Branch->getSuccessor(1))
      return DispatchSafety::UnsupportedControlFlow;
    bool EntryAlloca = Block.isEntryBlock();
    for (const auto &Instruction : Block) {
      if (auto *Alloca = llvm::dyn_cast<llvm::AllocaInst>(&Instruction)) {
        if (!EntryAlloca || !Alloca->isStaticAlloca() || Alloca->isSwiftError() ||
            Alloca->isUsedWithInAlloca())
          return DispatchSafety::UnsupportedControlFlow;
      } else {
        EntryAlloca = false;
      }
      if (auto *Call = llvm::dyn_cast<llvm::CallInst>(&Instruction)) {
        auto *Callee = Call->getCalledFunction();
        if (Call->isMustTailCall() || Call->isInlineAsm() ||
            Call->hasOperandBundles() || isEntrySetupIntrinsic(*Call) ||
            (Callee && Callee->isIntrinsic() && !Call->doesNotAccessMemory() &&
             !llvm::isa<llvm::AssumeInst, llvm::MemIntrinsic>(Call)))
          return DispatchSafety::UnsupportedControlFlow;
      }
      // RegToMem can try to spill a live register with a type that the
      // assembler or backend does not allow on the stack.
      auto *Type = Instruction.getType();
      if (Type->isTokenTy() || Type->isX86_AMXTy() ||
          Type->containsNonLocalTargetExtType())
        return DispatchSafety::UnsupportedType;
    }
  }
  return DispatchSafety::Safe;
}

bool rewriteDispatch(llvm::Function &Function,
                     llvm::FunctionAnalysisManager &Analysis,
                     const TransformOptions &Config, RewriteStatistics &Statistics) {
  unsigned Branches = 0;
  bool HasConditional = false;
  for (const auto &Block : Function) {
    auto *End = Block.getTerminator();
    Branches += isBranch(End);
    HasConditional |= conditionalBranch(End) != nullptr;
  }
  if (!HasConditional)
    return false;
  Statistics.CandidateSites += Branches;
  if (Function.size() > 32 || Function.getInstructionCount() > 384) {
    Statistics.SizeFilteredSites += Branches;
    return false;
  }
  switch (assessDispatch(Function)) {
  case DispatchSafety::UnsupportedControlFlow:
    ++Statistics.UnsafeControlFlowFunctions;
    return false;
  case DispatchSafety::UnsupportedType:
    Statistics.TypeFilteredSites += Branches;
    return false;
  case DispatchSafety::Safe:
    break;
  }
  Statistics.EligibleSites += Branches;
  if (Branches > Config.MaxSites) {
    Statistics.CapSkippedSites += Branches;
    return false;
  }

  // Dispatcher edges change dominance. Demote PHIs and cross-block values
  // before replacing edges, with critical edges split for PHI stores.
  llvm::RegToMemPass().run(Function, Analysis);
  auto &Entry = Function.getEntryBlock();
  auto FirstInstruction = Entry.begin();
  while (llvm::isa<llvm::AllocaInst>(*FirstInstruction))
    ++FirstInstruction;
  auto *First = Entry.splitBasicBlock(FirstInstruction, "dispatch.first");
  auto &Context = Function.getContext();
  auto *Dispatch = llvm::BasicBlock::Create(Context, "dispatch", &Function, First);
  auto *Invalid = llvm::BasicBlock::Create(Context, "dispatch.invalid", &Function);
  llvm::IRBuilder<>(Invalid).CreateUnreachable();
  llvm::SmallVector<llvm::BasicBlock *, 32> Blocks;
  for (auto &Block : Function)
    if (&Block != &Entry && &Block != Dispatch && &Block != Invalid)
      Blocks.push_back(&Block);

  uint64_t State = functionState(Function, Config.Seed);
  llvm::DenseMap<llvm::BasicBlock *, llvm::ConstantInt *> States;
  // Use a small linear table so every 32-bit label is valid. Older LLVM
  // DenseSet implementations reserve two integers as internal sentinels.
  llvm::SmallVector<uint32_t, 32> Used;
  llvm::IRBuilder<> Setup(Entry.getTerminator());
  for (auto *Block : Blocks) {
    uint32_t Label;
    do {
      Label = static_cast<uint32_t>(nextRandom(State));
    } while (llvm::is_contained(Used, Label));
    Used.push_back(Label);
    States[Block] = Setup.getInt32(Label);
  }
  auto *Slot = Setup.CreateAlloca(Setup.getInt32Ty(), nullptr, "dispatch.state");
  Setup.CreateStore(States.lookup(First), Slot, true);
  Entry.getTerminator()->setSuccessor(0, Dispatch);
  llvm::IRBuilder<> Router(Dispatch);
  auto *Switch = Router.CreateSwitch(Router.CreateLoad(Router.getInt32Ty(), Slot, true),
                                     Invalid, Blocks.size());
  for (auto *Block : Blocks) {
    Switch->addCase(States.lookup(Block), Block);
    auto *End = Block->getTerminator();
    llvm::IRBuilder<> Transition(End);
    Transition.SetCurrentDebugLocation(End->getDebugLoc());
    llvm::Value *Next;
    if (auto *Conditional = conditionalBranch(End)) {
      Next = Transition.CreateSelect(Conditional->getCondition(),
                                     States.lookup(Conditional->getSuccessor(0)),
                                     States.lookup(Conditional->getSuccessor(1)));
    } else if (isBranch(End)) {
      Next = States.lookup(End->getSuccessor(0));
    } else {
      continue;
    }
    Transition.CreateStore(Next, Slot, true);
    Transition.CreateBr(Dispatch);
    End->eraseFromParent();
  }
  Function.removeFnAttr(llvm::Attribute::Memory);
  Statistics.TransformedSites += Branches;
  ++Statistics.ChangedFunctions;
  return true;
}

static void splitComparison(ConditionalBranch &Branch,
                            llvm::ICmpInst &Comparison, uint64_t &State) {
  auto *Initial = Branch.getParent();
  auto *Join = Initial->splitBasicBlock(Branch.getIterator(),
                                       "native.compare.join");
  auto *Function = Initial->getParent();
  auto &Context = Function->getContext();
  auto *Lower = llvm::BasicBlock::Create(Context, "native.compare.lower",
                                         Function, Join);
  auto *Left = Comparison.getOperand(0);
  auto *Right = Comparison.getOperand(1);
  auto *Type = llvm::cast<llvm::IntegerType>(Left->getType());
  unsigned Width = Type->getBitWidth();
  unsigned Split = 1 + nextRandom(State) % (Width - 1);
  auto *Mask = llvm::ConstantInt::get(Type,
                                     llvm::APInt::getLowBitsSet(Width, Split));

  Initial->getTerminator()->eraseFromParent();
  llvm::IRBuilder<> Builder(Initial);
  Builder.SetCurrentDebugLocation(Branch.getDebugLoc());
  // The original comparison feeds only a branch, which already requires a
  // defined condition. Freeze before duplicating a potentially undef operand.
  if (!llvm::isGuaranteedNotToBeUndefOrPoison(Left))
    Left = Builder.CreateFreeze(Left, "native.compare.left");
  if (!llvm::isGuaranteedNotToBeUndefOrPoison(Right))
    Right = Builder.CreateFreeze(Right, "native.compare.right");
  bool Signed = Comparison.isSigned();
  auto *UpperLeft = Signed ? Builder.CreateAShr(Left, Split)
                           : Builder.CreateLShr(Left, Split);
  auto *UpperRight = Signed ? Builder.CreateAShr(Right, Split)
                            : Builder.CreateLShr(Right, Split);
  auto *UpperResult =
      Builder.CreateICmp(Comparison.getPredicate(), UpperLeft, UpperRight);
  Builder.CreateCondBr(Builder.CreateICmpEQ(UpperLeft, UpperRight), Lower, Join);

  Builder.SetInsertPoint(Lower);
  Builder.SetCurrentDebugLocation(Branch.getDebugLoc());
  // Equal upper portions leave only the unsigned order of the low bits.
  auto *LowerLeft = Builder.CreateAnd(Left, Mask);
  auto *LowerRight = Builder.CreateAnd(Right, Mask);
  auto *LowerResult =
      Builder.CreateICmp(Comparison.getUnsignedPredicate(), LowerLeft, LowerRight);
  Builder.CreateBr(Join);

  Builder.SetInsertPoint(&Branch);
  Builder.SetCurrentDebugLocation(Branch.getDebugLoc());
  auto *Result = Builder.CreatePHI(Builder.getInt1Ty(), 2, "native.compare.result");
  Result->addIncoming(UpperResult, Initial);
  Result->addIncoming(LowerResult, Lower);
  Branch.setCondition(Result);
  Comparison.eraseFromParent();
}

bool rewriteComparisons(llvm::Function &Function, const TransformOptions &Config,
                        RewriteStatistics &Statistics) {
  if (!canDiversify(Function, true)) {
    ++Statistics.UnsafeControlFlowFunctions;
    return false;
  }
  llvm::SmallVector<ConditionalBranch *, 4> Sites;
  for (llvm::BasicBlock &Block : Function) {
    auto *Branch = conditionalBranch(Block.getTerminator());
    if (!Branch)
      continue;
    auto *Comparison = llvm::dyn_cast<llvm::ICmpInst>(Branch->getCondition());
    if (!Comparison || Comparison->isEquality())
      continue;
    ++Statistics.CandidateSites;
    if (!hasSupportedWidth(Comparison->getOperand(0)->getType())) {
      ++Statistics.TypeFilteredSites;
      continue;
    }
    if (Comparison->hasSameSign()) {
      ++Statistics.FlagFilteredSites;
      continue;
    }
    if (!Comparison->hasOneUse() ||
        llvm::isa<llvm::Constant>(Comparison->getOperand(0)) ||
        llvm::isa<llvm::Constant>(Comparison->getOperand(1))) {
      ++Statistics.GuardFilteredSites;
      continue;
    }
    if (Block.isEHPad() || Block.hasAddressTaken()) {
      ++Statistics.UnsafeBlockSites;
      continue;
    }
    ++Statistics.EligibleSites;
    if (Sites.size() == Config.MaxSites) {
      ++Statistics.CapSkippedSites;
      continue;
    }
    Sites.push_back(Branch);
  }

  uint64_t State = functionState(Function, Config.Seed);
  for (auto *Branch : Sites)
    splitComparison(*Branch, *llvm::cast<llvm::ICmpInst>(Branch->getCondition()),
                    State);
  Statistics.TransformedSites += static_cast<unsigned>(Sites.size());
  if (!Sites.empty())
    ++Statistics.ChangedFunctions;
  return !Sites.empty();
}

} // namespace nativeobfuscator
